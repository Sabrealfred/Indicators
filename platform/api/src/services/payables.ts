import { all, bad, forbidden, newId, notFound, one, run, tx } from '../db.js';
import { nowIso, today } from '../clock.js';
import { deleteJournalsFor, getGl, glByKey, postJournal } from '../books.js';
import { Actor, Rail, createPayment, receiveFunds } from './banking.js';
import { audit } from './audit.js';
import { randomBytes } from 'node:crypto';

// ---------- Bills (accounts payable) ----------

export interface Bill {
  id: string;
  org_id: string;
  vendor_id: string;
  invoice_number: string | null;
  amount: number;
  issue_date: string;
  due_date: string;
  gl_account_id: string | null;
  memo: string | null;
  attachment_name: string | null;
  status: 'draft' | 'pending_approval' | 'approved' | 'scheduled' | 'paid' | 'void';
  payment_id: string | null;
  created_by: string | null;
  approved_by: string | null;
  created_at: string;
}

export function getBill(orgId: string, id: string): Bill {
  const b = one<Bill>('SELECT * FROM bills WHERE id = ? AND org_id = ?', id, orgId);
  if (!b) throw notFound('Bill');
  return b;
}

export function listBills(orgId: string) {
  return all<Bill & { vendor_name: string; overdue: number }>(
    `SELECT b.*, c.name AS vendor_name, (b.status IN ('approved','pending_approval','draft') AND b.due_date < ?) AS overdue
     FROM bills b JOIN counterparties c ON c.id = b.vendor_id WHERE b.org_id = ? ORDER BY b.due_date DESC`, today(), orgId,
  );
}

export function createBill(actor: Actor, input: {
  vendor_id: string; invoice_number?: string | null; amount: number; issue_date: string; due_date: string;
  gl_account_id?: string | null; memo?: string | null; attachment_name?: string | null; submit?: boolean;
}) {
  if (actor.role === 'employee') throw forbidden();
  if (!Number.isInteger(input.amount) || input.amount <= 0) throw bad('Amount must be positive cents');
  if (!one('SELECT 1 FROM counterparties WHERE id = ? AND org_id = ?', input.vendor_id, actor.org_id)) throw notFound('Vendor');
  if (input.gl_account_id) getGl(actor.org_id, input.gl_account_id);
  const id = newId('bill');
  run(
    `INSERT INTO bills(id, org_id, vendor_id, invoice_number, amount, issue_date, due_date, gl_account_id, memo, attachment_name, status, created_by, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    id, actor.org_id, input.vendor_id, input.invoice_number ?? null, input.amount, input.issue_date, input.due_date,
    input.gl_account_id ?? null, input.memo ?? null, input.attachment_name ?? null, input.submit ? 'pending_approval' : 'draft', actor.id, nowIso(),
  );
  run('UPDATE counterparties SET is_vendor = 1 WHERE id = ?', input.vendor_id);
  audit(actor, 'bill.created', 'bill', id, { amount: input.amount });
  return getBill(actor.org_id, id);
}

export function updateBill(actor: Actor, id: string, input: Partial<Pick<Bill, 'invoice_number' | 'amount' | 'issue_date' | 'due_date' | 'gl_account_id' | 'memo'>>) {
  const b = getBill(actor.org_id, id);
  if (!['draft', 'pending_approval'].includes(b.status)) throw bad('Only unapproved bills can be edited');
  const m = { ...b, ...input };
  run('UPDATE bills SET invoice_number = ?, amount = ?, issue_date = ?, due_date = ?, gl_account_id = ?, memo = ? WHERE id = ?',
    m.invoice_number, m.amount, m.issue_date, m.due_date, m.gl_account_id, m.memo, id);
  return getBill(actor.org_id, id);
}

export function submitBill(actor: Actor, id: string) {
  const b = getBill(actor.org_id, id);
  if (b.status !== 'draft') throw bad(`Bill is ${b.status}`);
  run("UPDATE bills SET status = 'pending_approval' WHERE id = ?", id);
  return getBill(actor.org_id, id);
}

/** Approval recognizes the expense and the payable (accrual basis). */
export function approveBill(actor: Actor, id: string) {
  if (actor.role !== 'admin') throw forbidden('Only admins can approve bills');
  return tx(() => {
    const b = getBill(actor.org_id, id);
    if (!['draft', 'pending_approval'].includes(b.status)) throw bad(`Bill is ${b.status}`);
    const vendor = one<{ name: string; default_gl_account_id: string | null }>('SELECT name, default_gl_account_id FROM counterparties WHERE id = ?', b.vendor_id)!;
    const expense = b.gl_account_id ?? vendor.default_gl_account_id ?? glByKey(actor.org_id, 'uncategorized_expense').id;
    postJournal(actor.org_id, {
      date: b.issue_date, memo: `Bill ${b.invoice_number ?? ''} — ${vendor.name}`.trim(), source_type: 'bill', source_id: b.id, created_by: actor.id,
      lines: [
        { gl_account_id: expense, amount: b.amount },
        { gl_account_id: glByKey(actor.org_id, 'ap').id, amount: -b.amount },
      ],
    });
    run("UPDATE bills SET status = 'approved', approved_by = ?, gl_account_id = ? WHERE id = ?", actor.id, expense, id);
    audit(actor, 'bill.approved', 'bill', id);
    return getBill(actor.org_id, id);
  });
}

export function payBill(actor: Actor, id: string, input: { account_id: string; rail: Rail; scheduled_for?: string | null }) {
  return tx(() => {
    const b = getBill(actor.org_id, id);
    if (b.status !== 'approved') throw bad('Approve the bill before paying it');
    const p = createPayment(actor, {
      account_id: input.account_id, counterparty_id: b.vendor_id, rail: input.rail, amount: b.amount,
      memo: b.invoice_number ? `Invoice ${b.invoice_number}` : b.memo ?? 'Bill payment', scheduled_for: input.scheduled_for ?? b.due_date,
      gl_account_id: glByKey(actor.org_id, 'ap').id, bill_id: b.id, skip_approval: true,
    });
    if (p.status === 'failed') throw bad(p.failure_reason ?? 'Payment failed');
    run("UPDATE bills SET status = ?, payment_id = ? WHERE id = ?", p.status === 'completed' ? 'paid' : 'scheduled', p.id, id);
    audit(actor, 'bill.payment_scheduled', 'bill', id, { payment_id: p.id });
    return { bill: getBill(actor.org_id, id), payment: p };
  });
}

export function voidBill(actor: Actor, id: string) {
  if (actor.role !== 'admin') throw forbidden();
  return tx(() => {
    const b = getBill(actor.org_id, id);
    if (['paid', 'scheduled', 'void'].includes(b.status)) throw bad(`A ${b.status} bill cannot be voided`);
    deleteJournalsFor(actor.org_id, 'bill', id);
    run("UPDATE bills SET status = 'void' WHERE id = ?", id);
    audit(actor, 'bill.voided', 'bill', id);
    return getBill(actor.org_id, id);
  });
}

// ---------- Invoices (accounts receivable) ----------

export interface Invoice {
  id: string;
  org_id: string;
  customer_id: string;
  number: string;
  issue_date: string;
  due_date: string;
  deposit_account_id: string;
  status: 'draft' | 'sent' | 'paid' | 'void';
  memo: string | null;
  total: number;
  amount_paid: number;
  public_token: string;
  sent_at: string | null;
  paid_at: string | null;
  created_at: string;
}

export interface InvoiceLineInput {
  description: string;
  quantity: number;
  unit_price: number;
  gl_account_id?: string | null;
}

export function getInvoice(orgId: string, id: string) {
  const inv = one<Invoice & { customer_name: string; customer_email: string | null }>(
    'SELECT i.*, c.name AS customer_name, c.email AS customer_email FROM invoices i JOIN counterparties c ON c.id = i.customer_id WHERE i.id = ? AND i.org_id = ?', id, orgId,
  );
  if (!inv) throw notFound('Invoice');
  const lines = all('SELECT * FROM invoice_lines WHERE invoice_id = ? ORDER BY id', id);
  return { ...inv, lines, overdue: inv.status === 'sent' && inv.due_date < today() };
}

export function listInvoices(orgId: string) {
  return all<Invoice & { customer_name: string }>(
    'SELECT i.*, c.name AS customer_name FROM invoices i JOIN counterparties c ON c.id = i.customer_id WHERE i.org_id = ? ORDER BY i.issue_date DESC, i.number DESC', orgId,
  ).map((i) => ({ ...i, overdue: i.status === 'sent' && i.due_date < today() }));
}

function nextInvoiceNumber(orgId: string) {
  const n = one<{ n: number }>('SELECT COUNT(*) AS n FROM invoices WHERE org_id = ?', orgId)!.n;
  return `INV-${String(1001 + n).padStart(5, '0')}`;
}

export function createInvoice(actor: Actor, input: {
  customer_id: string; issue_date: string; due_date: string; deposit_account_id: string; memo?: string | null; lines: InvoiceLineInput[];
}) {
  if (actor.role === 'employee') throw forbidden();
  if (!input.lines.length) throw bad('An invoice needs at least one line');
  if (!one('SELECT 1 FROM counterparties WHERE id = ? AND org_id = ?', input.customer_id, actor.org_id)) throw notFound('Customer');
  const dep = one<{ type: string }>('SELECT type FROM accounts WHERE id = ? AND org_id = ?', input.deposit_account_id, actor.org_id);
  if (!dep || dep.type === 'credit') throw bad('Deposit account must be a deposit account');
  return tx(() => {
    const id = newId('inv');
    const lines = input.lines.map((l) => ({ ...l, amount: Math.round(l.quantity * l.unit_price) }));
    const total = lines.reduce((s, l) => s + l.amount, 0);
    if (total <= 0) throw bad('Invoice total must be positive');
    run(
      `INSERT INTO invoices(id, org_id, customer_id, number, issue_date, due_date, deposit_account_id, status, memo, total, public_token, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, 'draft', ?, ?, ?, ?)`,
      id, actor.org_id, input.customer_id, nextInvoiceNumber(actor.org_id), input.issue_date, input.due_date, input.deposit_account_id,
      input.memo ?? null, total, randomBytes(16).toString('base64url'), nowIso(),
    );
    for (const l of lines) {
      if (l.gl_account_id) getGl(actor.org_id, l.gl_account_id);
      run('INSERT INTO invoice_lines(invoice_id, description, quantity, unit_price, amount, gl_account_id) VALUES (?, ?, ?, ?, ?, ?)',
        id, l.description, l.quantity, l.unit_price, l.amount, l.gl_account_id ?? null);
    }
    run('UPDATE counterparties SET is_customer = 1 WHERE id = ?', input.customer_id);
    audit(actor, 'invoice.created', 'invoice', id, { total });
    return getInvoice(actor.org_id, id);
  });
}

/** Sending recognizes revenue and the receivable. */
export function sendInvoice(actor: Actor, id: string) {
  return tx(() => {
    const inv = getInvoice(actor.org_id, id);
    if (inv.status !== 'draft') throw bad(`Invoice is ${inv.status}`);
    const revenueDefault = glByKey(actor.org_id, 'revenue').id;
    const byGl = new Map<string, number>();
    for (const l of inv.lines as { amount: number; gl_account_id: string | null }[]) {
      const g = l.gl_account_id ?? revenueDefault;
      byGl.set(g, (byGl.get(g) ?? 0) + l.amount);
    }
    postJournal(actor.org_id, {
      date: inv.issue_date, memo: `Invoice ${inv.number} — ${inv.customer_name}`, source_type: 'invoice', source_id: inv.id, created_by: actor.id,
      lines: [
        { gl_account_id: glByKey(actor.org_id, 'ar').id, amount: inv.total },
        ...[...byGl].map(([g, amt]) => ({ gl_account_id: g, amount: -amt })),
      ],
    });
    run("UPDATE invoices SET status = 'sent', sent_at = ? WHERE id = ?", nowIso(), id);
    audit(actor, 'invoice.sent', 'invoice', id);
    return getInvoice(actor.org_id, id);
  });
}

export function voidInvoice(actor: Actor, id: string) {
  return tx(() => {
    const inv = getInvoice(actor.org_id, id);
    if (inv.status === 'paid' || inv.amount_paid > 0) throw bad('Paid invoices cannot be voided');
    if (inv.status === 'void') throw bad('Invoice already void');
    deleteJournalsFor(actor.org_id, 'invoice', id);
    run("UPDATE invoices SET status = 'void' WHERE id = ?", id);
    audit(actor, 'invoice.voided', 'invoice', id);
    return getInvoice(actor.org_id, id);
  });
}

export function publicInvoice(token: string) {
  const row = one<{ id: string; org_id: string }>('SELECT id, org_id FROM invoices WHERE public_token = ?', token);
  if (!row) throw notFound('Invoice');
  const inv = getInvoice(row.org_id, row.id);
  const org = one<{ name: string; legal_name: string; address: string }>('SELECT name, legal_name, address FROM organizations WHERE id = ?', row.org_id)!;
  const acct = one<{ account_number: string; routing_number: string }>('SELECT account_number, routing_number FROM accounts WHERE id = ?', inv.deposit_account_id)!;
  return {
    number: inv.number, issue_date: inv.issue_date, due_date: inv.due_date, status: inv.status, memo: inv.memo, total: inv.total,
    amount_paid: inv.amount_paid, customer_name: inv.customer_name, lines: inv.lines, overdue: inv.overdue,
    from: org, pay_to: { routing_number: acct.routing_number, account_number_last4: acct.account_number.slice(-4) },
  };
}

/** Customer pays through the hosted invoice page (simulated ACH debit of their bank / card). */
export function payInvoice(token: string, amount?: number, method: 'ach' | 'card' = 'ach') {
  return tx(() => {
    const row = one<Invoice>('SELECT * FROM invoices WHERE public_token = ?', token);
    if (!row) throw notFound('Invoice');
    if (row.status !== 'sent') throw bad(`Invoice is ${row.status}`);
    const due = row.total - row.amount_paid;
    const amt = amount ?? due;
    if (amt <= 0 || amt > due) throw bad('Invalid payment amount');
    const customer = one<{ name: string }>('SELECT name FROM counterparties WHERE id = ?', row.customer_id)!;
    receiveFunds(row.org_id, row.deposit_account_id, {
      amount: amt, kind: method === 'card' ? 'invoice_card' : 'ach_in', counterparty_name: customer.name,
      description: `Payment for ${row.number}`, gl_account_id: glByKey(row.org_id, 'ar').id,
    });
    const paid = row.amount_paid + amt;
    run('UPDATE invoices SET amount_paid = ?, status = ?, paid_at = ? WHERE id = ?', paid, paid >= row.total ? 'paid' : 'sent', paid >= row.total ? nowIso() : null, row.id);
    audit(null, 'invoice.payment_received', 'invoice', row.id, { amount: amt }, row.org_id);
    return publicInvoice(token);
  });
}

// ---------- Reimbursements ----------

export function listReimbursements(actor: Actor) {
  const base = `SELECT r.*, u.name AS user_name FROM reimbursements r JOIN users u ON u.id = r.user_id WHERE r.org_id = ?`;
  return actor.role === 'employee'
    ? all(base + ' AND r.user_id = ? ORDER BY r.created_at DESC', actor.org_id, actor.id)
    : all(base + ' ORDER BY r.created_at DESC', actor.org_id);
}

export function submitReimbursement(actor: Actor, input: { merchant: string; amount: number; spent_on: string; gl_account_id?: string | null; description?: string | null; receipt_name?: string | null }) {
  if (!Number.isInteger(input.amount) || input.amount <= 0) throw bad('Amount must be positive cents');
  if (input.gl_account_id) getGl(actor.org_id, input.gl_account_id);
  const id = newId('reimb');
  run(
    `INSERT INTO reimbursements(id, org_id, user_id, merchant, amount, spent_on, gl_account_id, description, receipt_name, status, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'submitted', ?)`,
    id, actor.org_id, actor.id, input.merchant, input.amount, input.spent_on, input.gl_account_id ?? null, input.description ?? null, input.receipt_name ?? null, nowIso(),
  );
  audit(actor, 'reimbursement.submitted', 'reimbursement', id, { amount: input.amount });
  return one('SELECT * FROM reimbursements WHERE id = ?', id);
}

function employeeCounterparty(orgId: string, userId: string): string {
  const u = one<{ name: string; email: string; counterparty_id: string | null }>('SELECT * FROM users WHERE id = ?', userId)!;
  if (u.counterparty_id) return u.counterparty_id;
  const id = newId('cp');
  run(
    `INSERT INTO counterparties(id, org_id, name, email, type, ach_routing, ach_account, created_at) VALUES (?, ?, ?, ?, 'individual', '021000021', ?, ?)`,
    id, orgId, u.name, u.email, String(Math.floor(1e9 + Math.random() * 9e9)), nowIso(),
  );
  run('UPDATE users SET counterparty_id = ? WHERE id = ?', id, userId);
  return id;
}

export function reviewReimbursement(actor: Actor, id: string, decision: 'approve' | 'reject', payFromAccountId?: string) {
  if (actor.role !== 'admin') throw forbidden('Only admins can review reimbursements');
  return tx(() => {
    const r = one<{ id: string; user_id: string; amount: number; merchant: string; status: string; gl_account_id: string | null }>(
      'SELECT * FROM reimbursements WHERE id = ? AND org_id = ?', id, actor.org_id,
    );
    if (!r) throw notFound('Reimbursement');
    if (r.status !== 'submitted') throw bad(`Reimbursement is ${r.status}`);
    if (decision === 'reject') {
      run("UPDATE reimbursements SET status = 'rejected', reviewed_by = ? WHERE id = ?", actor.id, id);
      audit(actor, 'reimbursement.rejected', 'reimbursement', id);
      return one('SELECT * FROM reimbursements WHERE id = ?', id);
    }
    const from = payFromAccountId ?? one<{ id: string }>("SELECT id FROM accounts WHERE org_id = ? AND type = 'checking' AND status = 'open' ORDER BY created_at LIMIT 1", actor.org_id)?.id;
    if (!from) throw bad('No checking account to pay from');
    run("UPDATE reimbursements SET status = 'approved', reviewed_by = ? WHERE id = ?", actor.id, id);
    const p = createPayment(actor, {
      account_id: from, counterparty_id: employeeCounterparty(actor.org_id, r.user_id), rail: 'ach', amount: r.amount,
      memo: `Reimbursement: ${r.merchant}`, gl_account_id: r.gl_account_id ?? glByKey(actor.org_id, 'uncategorized_expense').id,
      reimbursement_id: r.id, skip_approval: true,
    });
    if (p.status !== 'failed') run('UPDATE reimbursements SET payment_id = ? WHERE id = ?', p.id, id);
    audit(actor, 'reimbursement.approved', 'reimbursement', id, { payment_id: p.id });
    return one('SELECT * FROM reimbursements WHERE id = ?', id);
  });
}
