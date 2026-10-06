import { AppError, all, bad, forbidden, newId, notFound, one, run, tx } from '../db.js';
import { addBusinessDays, addHours, now, nowIso, today } from '../clock.js';
import { SYSTEM, createLedgerAccount, ledgerBalance, postEntry } from '../ledger.js';
import { bookTransaction, createBankGlAccount } from '../books.js';
import { audit } from './audit.js';

export const ROUTING_NUMBER = '091311229';

export interface Account {
  id: string;
  org_id: string;
  name: string;
  type: 'checking' | 'savings' | 'treasury' | 'credit';
  account_number: string;
  routing_number: string;
  apy_bps: number;
  credit_limit: number;
  accrued_interest_micros: number;
  last_accrual_date: string | null;
  status: 'open' | 'frozen' | 'closed';
  ledger_account_id: string;
  gl_account_id: string;
  created_at: string;
}

export interface Payment {
  id: string;
  org_id: string;
  account_id: string;
  counterparty_id: string | null;
  to_account_id: string | null;
  rail: Rail;
  direction: 'outgoing' | 'incoming';
  amount: number;
  memo: string | null;
  status: 'pending_approval' | 'scheduled' | 'processing' | 'completed' | 'failed' | 'canceled' | 'returned';
  scheduled_for: string | null;
  settle_at: string | null;
  failure_reason: string | null;
  created_by: string | null;
  approved_by: string | null;
  hold_id: string | null;
  bill_id: string | null;
  reimbursement_id: string | null;
  invoice_id: string | null;
  gl_account_id: string | null;
  created_at: string;
  completed_at: string | null;
}

export type Rail = 'ach' | 'same_day_ach' | 'wire' | 'intl_wire' | 'book' | 'check';

export interface Actor {
  id: string;
  org_id: string;
  role: 'admin' | 'bookkeeper' | 'employee';
  name: string;
}

function randomDigits(n: number) {
  let s = '';
  for (let i = 0; i < n; i++) s += Math.floor(Math.random() * 10);
  return s;
}

// ---------- Accounts ----------

export function getAccount(orgId: string, id: string): Account {
  const a = one<Account>('SELECT * FROM accounts WHERE id = ? AND org_id = ?', id, orgId);
  if (!a) throw notFound('Account');
  return a;
}

export function createAccount(orgId: string, input: { name: string; type: Account['type']; apy_bps?: number; credit_limit?: number }, at = nowIso()): Account {
  return tx(() => {
    const id = newId('acct');
    const la = createLedgerAccount(`${input.type}:${input.name}`);
    const gl = createBankGlAccount(orgId, id, `${input.name}`, input.type === 'credit');
    let number = randomDigits(12);
    while (one('SELECT 1 FROM accounts WHERE account_number = ?', number)) number = randomDigits(12);
    run(
      `INSERT INTO accounts(id, org_id, name, type, account_number, routing_number, apy_bps, credit_limit, ledger_account_id, gl_account_id, last_accrual_date, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      id, orgId, input.name, input.type, number, ROUTING_NUMBER, input.apy_bps ?? 0, input.credit_limit ?? 0, la, gl, at.slice(0, 10), at,
    );
    return getAccount(orgId, id);
  });
}

export function balances(a: Account) {
  const current = ledgerBalance(a.ledger_account_id);
  const held = one<{ h: number }>("SELECT COALESCE(SUM(amount), 0) AS h FROM holds WHERE account_id = ? AND status = 'active'", a.id)!.h;
  const available = a.type === 'credit' ? a.credit_limit + current - held : current - held;
  return { current, pending: -held, available };
}

export function listAccounts(orgId: string) {
  return all<Account>("SELECT * FROM accounts WHERE org_id = ? AND status != 'closed' ORDER BY CASE type WHEN 'checking' THEN 0 WHEN 'savings' THEN 1 WHEN 'treasury' THEN 2 ELSE 3 END, created_at", orgId)
    .map((a) => ({ ...a, balance: balances(a) }));
}

// ---------- Holds & transactions ----------

export function addHold(accountId: string, amount: number, reason: string): string {
  const id = newId('hold');
  run('INSERT INTO holds(id, account_id, amount, reason, created_at) VALUES (?, ?, ?, ?, ?)', id, accountId, amount, reason, nowIso());
  return id;
}

export function releaseHold(id: string | null) {
  if (id) run("UPDATE holds SET status = 'released' WHERE id = ?", id);
}

export interface NewTx {
  org_id: string;
  account_id: string;
  amount: number;
  status: 'pending' | 'posted' | 'failed' | 'declined';
  kind: string;
  counterparty_name: string;
  description: string;
  mcc?: string | null;
  merchant_category?: string | null;
  gl_account_id?: string | null;
  card_id?: string | null;
  payment_id?: string | null;
  hold_id?: string | null;
  transfer_group?: string | null;
  decline_reason?: string | null;
}

export function insertTx(t: NewTx): string {
  const id = newId('txn');
  const at = nowIso();
  run(
    `INSERT INTO transactions(id, org_id, account_id, amount, status, kind, counterparty_name, description, mcc, merchant_category,
       gl_account_id, card_id, payment_id, hold_id, transfer_group, decline_reason, created_at, posted_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    id, t.org_id, t.account_id, t.amount, t.status, t.kind, t.counterparty_name, t.description, t.mcc ?? null, t.merchant_category ?? null,
    t.gl_account_id ?? null, t.card_id ?? null, t.payment_id ?? null, t.hold_id ?? null, t.transfer_group ?? null, t.decline_reason ?? null,
    at, t.status === 'posted' ? at : null,
  );
  return id;
}

/** Settles a pending transaction against the ledger and posts it to the books. */
export function postTx(txId: string, counterLedgerAccount: string, finalAmount?: number) {
  tx(() => {
    const t = one<{ id: string; account_id: string; amount: number; status: string; hold_id: string | null; description: string }>('SELECT * FROM transactions WHERE id = ?', txId);
    if (!t) throw notFound('Transaction');
    if (t.status !== 'pending') throw bad(`Transaction is ${t.status}`);
    const amount = finalAmount ?? t.amount;
    const acct = one<{ ledger_account_id: string }>('SELECT ledger_account_id FROM accounts WHERE id = ?', t.account_id)!;
    const entry = postEntry(t.description, [
      { account: acct.ledger_account_id, amount },
      { account: counterLedgerAccount, amount: -amount },
    ], { idempotencyKey: `tx:${txId}` });
    releaseHold(t.hold_id);
    run("UPDATE transactions SET status = 'posted', amount = ?, ledger_entry_id = ?, posted_at = ? WHERE id = ?", amount, entry, nowIso(), txId);
    bookTransaction(txId);
  });
}

/** Money arriving from outside (ACH credit, wire in, invoice payment, interest). Posts immediately. */
export function receiveFunds(orgId: string, accountId: string, input: {
  amount: number; kind: string; counterparty_name: string; description: string; rail?: Rail;
  gl_account_id?: string | null; payment_id?: string | null; merchant_category?: string | null; counter?: string;
}) {
  if (input.amount <= 0) throw bad('Amount must be positive');
  const acct = getAccount(orgId, accountId);
  return tx(() => {
    const id = insertTx({
      org_id: orgId, account_id: acct.id, amount: input.amount, status: 'pending', kind: input.kind,
      counterparty_name: input.counterparty_name, description: input.description, gl_account_id: input.gl_account_id,
      payment_id: input.payment_id, merchant_category: input.merchant_category,
    });
    postTx(id, input.counter ?? railClearing(input.rail ?? 'ach'));
    return id;
  });
}

export function railClearing(rail: Rail): string {
  if (rail === 'wire' || rail === 'intl_wire') return SYSTEM.wire;
  if (rail === 'check') return SYSTEM.check;
  return SYSTEM.ach;
}

// ---------- Payments ----------

export function getPayment(orgId: string, id: string): Payment {
  const p = one<Payment>('SELECT * FROM payments WHERE id = ? AND org_id = ?', id, orgId);
  if (!p) throw notFound('Payment');
  return p;
}

function settleTime(rail: Rail, from: Date): Date {
  switch (rail) {
    case 'wire': return addHours(from, 0.25);
    case 'same_day_ach': return addHours(from, 4);
    case 'ach': return addBusinessDays(from, 1);
    case 'intl_wire': return addBusinessDays(from, 1);
    case 'check': return addBusinessDays(from, 5);
    case 'book': return from;
  }
}

export interface PaymentInput {
  account_id: string;
  counterparty_id?: string | null;
  to_account_id?: string | null;
  rail: Rail;
  amount: number;
  memo?: string | null;
  scheduled_for?: string | null;
  gl_account_id?: string | null;
  bill_id?: string | null;
  reimbursement_id?: string | null;
  idempotency_key?: string | null;
  skip_approval?: boolean;
}

export function createPayment(actor: Actor, input: PaymentInput): Payment {
  if (!Number.isInteger(input.amount) || input.amount <= 0) throw bad('Amount must be a positive integer number of cents');
  if (actor.role === 'employee') throw forbidden('Employees cannot send payments');
  return tx(() => {
    if (input.idempotency_key) {
      const existing = one<Payment>('SELECT * FROM payments WHERE idempotency_key = ? AND org_id = ?', input.idempotency_key, actor.org_id);
      if (existing) return existing;
    }
    const from = getAccount(actor.org_id, input.account_id);
    if (from.status !== 'open') throw bad('Source account is not open');
    if (from.type === 'credit') throw bad('Payments cannot be sent from a credit account');
    if (input.rail === 'book') {
      if (!input.to_account_id) throw bad('Book transfers need to_account_id');
      if (input.to_account_id === input.account_id) throw bad('Cannot transfer to the same account');
      getAccount(actor.org_id, input.to_account_id);
    } else {
      if (!input.counterparty_id) throw bad('External payments need a recipient');
      const cp = one<{ ach_account: string | null; wire_account: string | null; iban: string | null; address: string | null }>(
        'SELECT * FROM counterparties WHERE id = ? AND org_id = ?', input.counterparty_id, actor.org_id,
      );
      if (!cp) throw notFound('Recipient');
      if ((input.rail === 'ach' || input.rail === 'same_day_ach') && !cp.ach_account) throw bad('Recipient has no ACH details');
      if (input.rail === 'wire' && !cp.wire_account) throw bad('Recipient has no domestic wire details');
      if (input.rail === 'intl_wire' && !cp.iban) throw bad('Recipient has no international wire details');
      if (input.rail === 'check' && !cp.address) throw bad('Recipient has no mailing address');
    }
    const org = one<{ approval_threshold: number }>('SELECT approval_threshold FROM organizations WHERE id = ?', actor.org_id)!;
    const needsApproval = !input.skip_approval && input.rail !== 'book' && (input.amount >= org.approval_threshold || actor.role !== 'admin');
    const sched = input.scheduled_for && input.scheduled_for > today() ? input.scheduled_for : null;
    const id = newId('pmt');
    run(
      `INSERT INTO payments(id, org_id, account_id, counterparty_id, to_account_id, rail, direction, amount, memo, status, scheduled_for,
         created_by, bill_id, reimbursement_id, gl_account_id, idempotency_key, created_at)
       VALUES (?, ?, ?, ?, ?, ?, 'outgoing', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      id, actor.org_id, input.account_id, input.counterparty_id ?? null, input.to_account_id ?? null, input.rail, input.amount,
      input.memo ?? null, needsApproval ? 'pending_approval' : 'scheduled', sched, actor.id,
      input.bill_id ?? null, input.reimbursement_id ?? null, input.gl_account_id ?? null, input.idempotency_key ?? null, nowIso(),
    );
    audit(actor, 'payment.created', 'payment', id, { amount: input.amount, rail: input.rail });
    if (!needsApproval) release(getPayment(actor.org_id, id));
    return getPayment(actor.org_id, id);
  });
}

export function approvePayment(actor: Actor, id: string): Payment {
  if (actor.role !== 'admin') throw forbidden('Only admins can approve payments');
  return tx(() => {
    const p = getPayment(actor.org_id, id);
    if (p.status !== 'pending_approval') throw bad(`Payment is ${p.status}`);
    const org = one<{ approval_threshold: number }>('SELECT approval_threshold FROM organizations WHERE id = ?', actor.org_id)!;
    const admins = one<{ n: number }>("SELECT COUNT(*) AS n FROM users WHERE org_id = ? AND role = 'admin' AND status = 'active'", actor.org_id)!.n;
    if (p.created_by === actor.id && p.amount >= org.approval_threshold && admins > 1) {
      throw forbidden('Payments above the approval threshold need a second admin');
    }
    run("UPDATE payments SET status = 'scheduled', approved_by = ? WHERE id = ?", actor.id, id);
    audit(actor, 'payment.approved', 'payment', id);
    release(getPayment(actor.org_id, id));
    return getPayment(actor.org_id, id);
  });
}

export function cancelPayment(actor: Actor, id: string): Payment {
  return tx(() => {
    const p = getPayment(actor.org_id, id);
    if (!['pending_approval', 'scheduled'].includes(p.status)) throw bad(`A ${p.status} payment cannot be canceled`);
    run("UPDATE payments SET status = 'canceled' WHERE id = ?", id);
    if (p.bill_id) run("UPDATE bills SET status = 'approved', payment_id = NULL WHERE id = ?", p.bill_id);
    if (p.reimbursement_id) run("UPDATE reimbursements SET status = 'approved', payment_id = NULL WHERE id = ?", p.reimbursement_id);
    audit(actor, 'payment.canceled', 'payment', id);
    return getPayment(actor.org_id, id);
  });
}

function counterpartyName(p: Payment): string {
  if (p.counterparty_id) return one<{ name: string }>('SELECT name FROM counterparties WHERE id = ?', p.counterparty_id)!.name;
  return 'Transfer';
}

const RAIL_LABEL: Record<Rail, string> = {
  ach: 'ACH', same_day_ach: 'Same-day ACH', wire: 'Domestic wire', intl_wire: 'International wire', book: 'Transfer', check: 'Check',
};

/** Moves a scheduled payment forward if its date has come: book transfers settle, external ones start processing. */
function release(p: Payment) {
  if (p.status !== 'scheduled') return;
  if (p.scheduled_for && p.scheduled_for > today()) return;
  const from = getAccount(p.org_id, p.account_id);
  if (balances(from).available < p.amount) {
    run("UPDATE payments SET status = 'failed', failure_reason = 'Insufficient funds' WHERE id = ?", p.id);
    insertTx({
      org_id: p.org_id, account_id: p.account_id, amount: -p.amount, status: 'failed', kind: `${p.rail}_out`,
      counterparty_name: counterpartyName(p), description: `${RAIL_LABEL[p.rail]} — insufficient funds`, payment_id: p.id,
    });
    if (p.bill_id) run("UPDATE bills SET status = 'approved', payment_id = NULL WHERE id = ?", p.bill_id);
    if (p.reimbursement_id) run("UPDATE reimbursements SET status = 'approved', payment_id = NULL WHERE id = ?", p.reimbursement_id);
    return;
  }
  if (p.rail === 'book') {
    settleBookTransfer(p);
    return;
  }
  const hold = addHold(p.account_id, p.amount, `payment:${p.id}`);
  insertTx({
    org_id: p.org_id, account_id: p.account_id, amount: -p.amount, status: 'pending', kind: `${p.rail}_out`,
    counterparty_name: counterpartyName(p), description: p.memo || RAIL_LABEL[p.rail], payment_id: p.id, hold_id: hold,
    gl_account_id: p.gl_account_id,
  });
  run("UPDATE payments SET status = 'processing', hold_id = ?, settle_at = ? WHERE id = ?", hold, settleTime(p.rail, now()).toISOString(), p.id);
}

function settleBookTransfer(p: Payment) {
  const to = getAccount(p.org_id, p.to_account_id!);
  const from = getAccount(p.org_id, p.account_id);
  const group = newId('xfer');
  const desc = p.memo || `Transfer ${from.name} → ${to.name}`;
  const out = insertTx({ org_id: p.org_id, account_id: from.id, amount: -p.amount, status: 'pending', kind: 'transfer_out', counterparty_name: to.name, description: desc, payment_id: p.id, transfer_group: group });
  const inn = insertTx({ org_id: p.org_id, account_id: to.id, amount: p.amount, status: 'pending', kind: 'transfer_in', counterparty_name: from.name, description: desc, payment_id: p.id, transfer_group: group });
  const entry = postEntry(desc, [
    { account: from.ledger_account_id, amount: -p.amount },
    { account: to.ledger_account_id, amount: p.amount },
  ], { idempotencyKey: `xfer:${p.id}` });
  const at = nowIso();
  run("UPDATE transactions SET status = 'posted', ledger_entry_id = ?, posted_at = ? WHERE id IN (?, ?)", entry, at, out, inn);
  run("UPDATE payments SET status = 'completed', settle_at = ?, completed_at = ? WHERE id = ?", at, at, p.id);
  bookTransaction(inn);
}

function settlePayment(p: Payment) {
  const t = one<{ id: string }>("SELECT id FROM transactions WHERE payment_id = ? AND status = 'pending'", p.id);
  if (t) postTx(t.id, railClearing(p.rail));
  run("UPDATE payments SET status = 'completed', completed_at = ? WHERE id = ?", nowIso(), p.id);
  if (p.bill_id) run("UPDATE bills SET status = 'paid' WHERE id = ?", p.bill_id);
  if (p.reimbursement_id) run("UPDATE reimbursements SET status = 'paid' WHERE id = ?", p.reimbursement_id);
}

/** Simulates the receiving bank rejecting an ACH after settlement (e.g. R03 no account). */
export function returnPayment(actor: Actor, id: string, reason = 'R03 — No account / unable to locate account') {
  return tx(() => {
    const p = getPayment(actor.org_id, id);
    if (p.rail !== 'ach' && p.rail !== 'same_day_ach') throw bad('Only ACH payments can be returned');
    if (p.status === 'processing') {
      const t = one<{ id: string; hold_id: string | null }>("SELECT id, hold_id FROM transactions WHERE payment_id = ? AND status = 'pending'", p.id);
      if (t) {
        releaseHold(t.hold_id);
        run("UPDATE transactions SET status = 'failed', decline_reason = ? WHERE id = ?", reason, t.id);
      }
      run("UPDATE payments SET status = 'failed', failure_reason = ? WHERE id = ?", reason, id);
    } else if (p.status === 'completed') {
      receiveFunds(actor.org_id, p.account_id, {
        amount: p.amount, kind: 'ach_return', counterparty_name: counterpartyName(p), description: `ACH return: ${reason}`,
        gl_account_id: one<{ gl_account_id: string }>("SELECT gl_account_id FROM transactions WHERE payment_id = ? AND status = 'posted'", p.id)?.gl_account_id,
        payment_id: p.id,
      });
      run("UPDATE payments SET status = 'returned', failure_reason = ? WHERE id = ?", reason, id);
    } else {
      throw bad(`A ${p.status} payment cannot be returned`);
    }
    if (p.bill_id) run("UPDATE bills SET status = 'approved', payment_id = NULL WHERE id = ?", p.bill_id);
    if (p.reimbursement_id) run("UPDATE reimbursements SET status = 'approved', payment_id = NULL WHERE id = ?", p.reimbursement_id);
    audit(actor, 'payment.returned', 'payment', id, { reason });
    return getPayment(actor.org_id, id);
  });
}

/** Called by the processor: releases due scheduled payments and settles processing ones. */
export function processPayments() {
  const due = all<Payment>("SELECT * FROM payments WHERE status = 'scheduled' AND (scheduled_for IS NULL OR scheduled_for <= ?)", today());
  for (const p of due) tx(() => release(p));
  const settling = all<Payment>("SELECT * FROM payments WHERE status = 'processing' AND settle_at <= ?", nowIso());
  for (const p of settling) tx(() => settlePayment(p));
  return { released: due.length, settled: settling.length };
}

export function assertRole(actor: Actor, ...roles: Actor['role'][]) {
  if (!roles.includes(actor.role)) throw new AppError(403, `Requires role: ${roles.join(' or ')}`, 'forbidden');
}
