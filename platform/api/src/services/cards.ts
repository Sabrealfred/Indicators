import { all, bad, forbidden, newId, notFound, one, run, tx } from '../db.js';
import { addDays, now, nowIso } from '../clock.js';
import { SYSTEM, postEntry } from '../ledger.js';
import { bookTransaction } from '../books.js';
import { Actor, addHold, balances, getAccount, insertTx, postTx, releaseHold } from './banking.js';
import { audit } from './audit.js';

export interface Card {
  id: string;
  org_id: string;
  account_id: string;
  user_id: string;
  nickname: string;
  form: 'virtual' | 'physical';
  network: string;
  pan: string;
  last4: string;
  exp_month: number;
  exp_year: number;
  cvv: string;
  status: 'active' | 'frozen' | 'canceled';
  spend_limit: number | null;
  limit_interval: 'transaction' | 'daily' | 'monthly' | 'all_time' | null;
  blocked_categories: string;
  created_at: string;
}

const BIN = '552433';

function luhnCheckDigit(partial: string) {
  let sum = 0;
  for (let i = 0; i < partial.length; i++) {
    let d = Number(partial[partial.length - 1 - i]);
    if (i % 2 === 0) {
      d *= 2;
      if (d > 9) d -= 9;
    }
    sum += d;
  }
  return String((10 - (sum % 10)) % 10);
}

export function luhnValid(pan: string) {
  return luhnCheckDigit(pan.slice(0, -1)) === pan.slice(-1);
}

function generatePan() {
  let body = BIN;
  while (body.length < 15) body += Math.floor(Math.random() * 10);
  return body + luhnCheckDigit(body);
}

export function publicCard(c: Card) {
  const { pan, cvv, ...rest } = c;
  return { ...rest, blocked_categories: JSON.parse(c.blocked_categories) as string[], spent_in_window: spentInWindow(c) };
}

export function getCard(orgId: string, id: string): Card {
  const c = one<Card>('SELECT * FROM cards WHERE id = ? AND org_id = ?', id, orgId);
  if (!c) throw notFound('Card');
  return c;
}

function canManage(actor: Actor, c: Card) {
  return actor.role === 'admin' || c.user_id === actor.id;
}

export function listCards(actor: Actor) {
  const rows = actor.role === 'employee'
    ? all<Card>('SELECT * FROM cards WHERE org_id = ? AND user_id = ? ORDER BY created_at DESC', actor.org_id, actor.id)
    : all<Card>('SELECT * FROM cards WHERE org_id = ? ORDER BY created_at DESC', actor.org_id);
  return rows.map(publicCard);
}

export function issueCard(actor: Actor, input: {
  account_id: string; user_id: string; nickname: string; form: Card['form'];
  spend_limit?: number | null; limit_interval?: Card['limit_interval']; blocked_categories?: string[];
}) {
  if (actor.role !== 'admin') throw forbidden('Only admins can issue cards');
  const acct = getAccount(actor.org_id, input.account_id);
  if (acct.type !== 'checking' && acct.type !== 'credit') throw bad('Cards can only draw on checking or credit accounts');
  if (!one('SELECT 1 FROM users WHERE id = ? AND org_id = ?', input.user_id, actor.org_id)) throw notFound('Cardholder');
  if (input.spend_limit != null && !input.limit_interval) throw bad('A spend limit needs an interval');
  const id = newId('card');
  let pan = generatePan();
  while (one('SELECT 1 FROM cards WHERE pan = ?', pan)) pan = generatePan();
  const exp = addDays(now(), 365 * 4);
  run(
    `INSERT INTO cards(id, org_id, account_id, user_id, nickname, form, pan, last4, exp_month, exp_year, cvv, spend_limit, limit_interval, blocked_categories, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    id, actor.org_id, acct.id, input.user_id, input.nickname, input.form, pan, pan.slice(-4), exp.getUTCMonth() + 1, exp.getUTCFullYear(),
    String(100 + Math.floor(Math.random() * 900)), input.spend_limit ?? null, input.limit_interval ?? null,
    JSON.stringify(input.blocked_categories ?? []), nowIso(),
  );
  audit(actor, 'card.issued', 'card', id, { nickname: input.nickname, account: acct.name });
  return publicCard(getCard(actor.org_id, id));
}

export function updateCard(actor: Actor, id: string, input: Partial<Pick<Card, 'nickname' | 'status' | 'spend_limit' | 'limit_interval'>> & { blocked_categories?: string[] }) {
  const c = getCard(actor.org_id, id);
  if (!canManage(actor, c)) throw forbidden();
  if (c.status === 'canceled') throw bad('Card is canceled');
  if (actor.role !== 'admin' && (input.spend_limit !== undefined || input.limit_interval !== undefined || input.blocked_categories || input.status === 'canceled')) {
    throw forbidden('Only admins can change limits or cancel cards');
  }
  run(
    'UPDATE cards SET nickname = ?, status = ?, spend_limit = ?, limit_interval = ?, blocked_categories = ? WHERE id = ?',
    input.nickname ?? c.nickname, input.status ?? c.status,
    input.spend_limit !== undefined ? input.spend_limit : c.spend_limit,
    input.limit_interval !== undefined ? input.limit_interval : c.limit_interval,
    input.blocked_categories ? JSON.stringify(input.blocked_categories) : c.blocked_categories, id,
  );
  audit(actor, 'card.updated', 'card', id, input);
  return publicCard(getCard(actor.org_id, id));
}

export function revealCard(actor: Actor, id: string) {
  const c = getCard(actor.org_id, id);
  if (!canManage(actor, c)) throw forbidden();
  audit(actor, 'card.revealed', 'card', id);
  return { pan: c.pan, cvv: c.cvv, exp_month: c.exp_month, exp_year: c.exp_year };
}

function windowStart(interval: Card['limit_interval']): string | null {
  const n = now();
  if (interval === 'daily') return n.toISOString().slice(0, 10);
  if (interval === 'monthly') return n.toISOString().slice(0, 7) + '-01';
  if (interval === 'all_time') return '0000';
  return null;
}

export function spentInWindow(c: Card): number {
  const start = windowStart(c.limit_interval);
  if (!start) return 0;
  return -one<{ s: number }>(
    "SELECT COALESCE(SUM(amount), 0) AS s FROM transactions WHERE card_id = ? AND status IN ('pending','posted') AND kind = 'card' AND created_at >= ?",
    c.id, start,
  )!.s;
}

/** Card network authorization request. Returns the resulting transaction (approved = pending, or declined). */
export function authorize(orgId: string, cardId: string, input: { merchant: string; amount: number; merchant_category?: string; mcc?: string }) {
  if (!Number.isInteger(input.amount) || input.amount <= 0) throw bad('Amount must be positive cents');
  return tx(() => {
    const c = getCard(orgId, cardId);
    const acct = getAccount(orgId, c.account_id);
    let decline: string | null = null;
    const n = now();
    if (c.status !== 'active') decline = `Card is ${c.status}`;
    else if (c.exp_year < n.getUTCFullYear() || (c.exp_year === n.getUTCFullYear() && c.exp_month < n.getUTCMonth() + 1)) decline = 'Card expired';
    else if (acct.status !== 'open') decline = 'Account is not open';
    else if (input.merchant_category && (JSON.parse(c.blocked_categories) as string[]).includes(input.merchant_category)) decline = `Merchant category ${input.merchant_category} is blocked`;
    else if (c.spend_limit != null && c.limit_interval === 'transaction' && input.amount > c.spend_limit) decline = 'Exceeds per-transaction limit';
    else if (c.spend_limit != null && c.limit_interval !== 'transaction' && spentInWindow(c) + input.amount > c.spend_limit) decline = `Exceeds ${c.limit_interval} card limit`;
    else if (balances(acct).available < input.amount) decline = acct.type === 'credit' ? 'Insufficient credit' : 'Insufficient funds';

    const base = {
      org_id: orgId, account_id: acct.id, amount: -input.amount, kind: 'card', counterparty_name: input.merchant,
      description: `Card ••${c.last4}`, merchant_category: input.merchant_category ?? null, mcc: input.mcc ?? null, card_id: c.id,
    };
    if (decline) {
      const id = insertTx({ ...base, status: 'declined', decline_reason: decline });
      return { approved: false, decline_reason: decline, transaction_id: id };
    }
    const hold = addHold(acct.id, input.amount, `card:${c.id}`);
    const id = insertTx({ ...base, status: 'pending', hold_id: hold });
    return { approved: true, decline_reason: null, transaction_id: id };
  });
}

/** Merchant captures the authorization; final amount can differ (tips, fuel). */
export function capture(orgId: string, txId: string, finalAmount?: number) {
  const t = one<{ id: string; status: string; amount: number; kind: string }>('SELECT * FROM transactions WHERE id = ? AND org_id = ?', txId, orgId);
  if (!t || t.kind !== 'card') throw notFound('Card transaction');
  postTx(txId, SYSTEM.card, finalAmount != null ? -finalAmount : undefined);
}

export function voidAuthorization(orgId: string, txId: string) {
  tx(() => {
    const t = one<{ id: string; status: string; hold_id: string | null; kind: string }>('SELECT * FROM transactions WHERE id = ? AND org_id = ?', txId, orgId);
    if (!t || t.kind !== 'card') throw notFound('Card transaction');
    if (t.status !== 'pending') throw bad('Only pending authorizations can be voided');
    releaseHold(t.hold_id);
    run("UPDATE transactions SET status = 'reversed' WHERE id = ?", txId);
  });
}

export function refund(orgId: string, txId: string, amount?: number) {
  return tx(() => {
    const t = one<{ id: string; status: string; amount: number; kind: string; account_id: string; card_id: string; counterparty_name: string; merchant_category: string | null; gl_account_id: string | null }>(
      'SELECT * FROM transactions WHERE id = ? AND org_id = ?', txId, orgId,
    );
    if (!t || t.kind !== 'card' || t.status !== 'posted') throw bad('Only posted card purchases can be refunded');
    const amt = amount ?? -t.amount;
    if (amt <= 0 || amt > -t.amount) throw bad('Refund exceeds purchase amount');
    const id = insertTx({
      org_id: orgId, account_id: t.account_id, amount: amt, status: 'pending', kind: 'card_refund', counterparty_name: t.counterparty_name,
      description: 'Card refund', card_id: t.card_id, merchant_category: t.merchant_category, gl_account_id: t.gl_account_id,
    });
    postTx(id, SYSTEM.card);
    return id;
  });
}

/** Captures card authorizations older than a day, as merchants normally do. */
export function captureStaleAuthorizations() {
  const cutoff = addDays(now(), -1).toISOString();
  const rows = all<{ id: string; org_id: string }>("SELECT id, org_id FROM transactions WHERE kind = 'card' AND status = 'pending' AND created_at <= ?", cutoff);
  for (const r of rows) capture(r.org_id, r.id);
  return rows.length;
}

/** IO-style credit card month end: 1.5% cashback on last month's net purchases, then autopay in full from checking. */
export function creditMonthEnd(orgId: string, monthStart: string, prevMonthStart: string) {
  for (const acct of all<{ id: string; ledger_account_id: string; name: string }>("SELECT * FROM accounts WHERE org_id = ? AND type = 'credit' AND status = 'open'", orgId)) {
    tx(() => {
      const spend = -one<{ s: number }>(
        "SELECT COALESCE(SUM(amount), 0) AS s FROM transactions WHERE account_id = ? AND kind IN ('card','card_refund') AND status = 'posted' AND posted_at >= ? AND posted_at < ?",
        acct.id, prevMonthStart, monthStart,
      )!.s;
      const cashback = Math.floor(spend * 0.015);
      if (cashback > 0) {
        const id = insertTx({ org_id: orgId, account_id: acct.id, amount: cashback, status: 'pending', kind: 'cashback', counterparty_name: 'Cashback', description: `1.5% cashback on ${prevMonthStart.slice(0, 7)} purchases` });
        postTx(id, SYSTEM.rewards);
      }
      const full = getAccount(orgId, acct.id);
      const owed = -balances(full).current;
      if (owed <= 0) return;
      const checking = one<{ id: string }>("SELECT id FROM accounts WHERE org_id = ? AND type = 'checking' AND status = 'open' ORDER BY created_at LIMIT 1", orgId);
      if (!checking) return;
      const chk = getAccount(orgId, checking.id);
      if (balances(chk).available < owed) return;
      const group = newId('xfer');
      const desc = `Autopay ${acct.name} statement ${prevMonthStart.slice(0, 7)}`;
      const out = insertTx({ org_id: orgId, account_id: chk.id, amount: -owed, status: 'pending', kind: 'transfer_out', counterparty_name: acct.name, description: desc, transfer_group: group });
      const inn = insertTx({ org_id: orgId, account_id: acct.id, amount: owed, status: 'pending', kind: 'transfer_in', counterparty_name: chk.name, description: desc, transfer_group: group });
      const entry = postEntry(desc, [{ account: chk.ledger_account_id, amount: -owed }, { account: acct.ledger_account_id, amount: owed }]);
      run("UPDATE transactions SET status = 'posted', ledger_entry_id = ?, posted_at = ? WHERE id IN (?, ?)", entry, nowIso(), out, inn);
      bookTransaction(inn);
    });
  }
}
