import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { all, bad, newId, notFound, one, run } from '../db.js';
import { nowIso, today } from '../clock.js';
import { ledgerBalanceAt } from '../ledger.js';
import { recategorize } from '../books.js';
import {
  approvePayment, assertRole, balances, cancelPayment, createAccount, createPayment, getAccount, listAccounts,
} from '../services/banking.js';
import { audit } from '../services/audit.js';

const rail = z.enum(['ach', 'same_day_ach', 'wire', 'intl_wire', 'book', 'check']);
const cents = z.number().int().positive();
const date = z.string().regex(/^\d{4}-\d{2}-\d{2}$/);

function csv(rows: Record<string, unknown>[]) {
  if (!rows.length) return '';
  const cols = Object.keys(rows[0]);
  const esc = (v: unknown) => {
    const s = v == null ? '' : String(v);
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  return [cols.join(','), ...rows.map((r) => cols.map((c) => esc(r[c])).join(','))].join('\n');
}

const TX_SELECT = `SELECT t.*, a.name AS account_name, g.name AS category_name, g.code AS category_code, c.last4 AS card_last4, u.name AS cardholder
  FROM transactions t JOIN accounts a ON a.id = t.account_id
  LEFT JOIN gl_accounts g ON g.id = t.gl_account_id
  LEFT JOIN cards c ON c.id = t.card_id
  LEFT JOIN users u ON u.id = c.user_id`;

const ownsCard = (userId: string, cardId: string | null) => !!cardId && !!one('SELECT 1 FROM cards WHERE id = ? AND user_id = ?', cardId, userId);

export default async function bankingRoutes(app: FastifyInstance) {
  // ----- Accounts -----
  app.get('/accounts', async (req) => listAccounts(req.actor.org_id));

  app.post('/accounts', async (req) => {
    assertRole(req.actor, 'admin');
    const body = z.object({
      name: z.string().min(1), type: z.enum(['checking', 'savings', 'treasury', 'credit']),
      apy_bps: z.number().int().min(0).max(2000).optional(), credit_limit: z.number().int().min(0).optional(),
    }).parse(req.body);
    const a = createAccount(req.actor.org_id, body);
    audit(req.actor, 'account.opened', 'account', a.id, body);
    return { ...a, balance: balances(a) };
  });

  app.get('/accounts/:id', async (req) => {
    const a = getAccount(req.actor.org_id, (req.params as { id: string }).id);
    return { ...a, balance: balances(a) };
  });

  app.patch('/accounts/:id', async (req) => {
    assertRole(req.actor, 'admin');
    const a = getAccount(req.actor.org_id, (req.params as { id: string }).id);
    const body = z.object({ name: z.string().min(1).optional(), status: z.enum(['open', 'frozen', 'closed']).optional() }).parse(req.body);
    if (body.status === 'closed' && balances(a).current !== 0) throw bad('Only zero-balance accounts can be closed');
    run('UPDATE accounts SET name = ?, status = ? WHERE id = ?', body.name ?? a.name, body.status ?? a.status, a.id);
    audit(req.actor, 'account.updated', 'account', a.id, body);
    return getAccount(req.actor.org_id, a.id);
  });

  app.get('/accounts/:id/statements', async (req) => {
    const a = getAccount(req.actor.org_id, (req.params as { id: string }).id);
    const months: string[] = [];
    const d = new Date(a.created_at.slice(0, 7) + '-01T00:00:00Z');
    const end = today().slice(0, 7);
    while (d.toISOString().slice(0, 7) < end) {
      months.push(d.toISOString().slice(0, 7));
      d.setUTCMonth(d.getUTCMonth() + 1);
    }
    return months.reverse();
  });

  app.get('/accounts/:id/statements/:month', async (req, reply) => {
    const { id, month } = req.params as { id: string; month: string };
    if (!/^\d{4}-\d{2}$/.test(month)) throw bad('Month must be YYYY-MM');
    const a = getAccount(req.actor.org_id, id);
    const start = `${month}-01T00:00:00.000Z`;
    const n = new Date(start);
    n.setUTCMonth(n.getUTCMonth() + 1);
    const end = n.toISOString();
    const txs = all<Record<string, unknown>>(
      "SELECT posted_at, counterparty_name, description, kind, amount FROM transactions WHERE account_id = ? AND status = 'posted' AND posted_at >= ? AND posted_at < ? ORDER BY posted_at",
      a.id, start, end,
    );
    const statement = {
      account: { name: a.name, type: a.type, account_number: a.account_number, routing_number: a.routing_number },
      month, opening_balance: ledgerBalanceAt(a.ledger_account_id, start), closing_balance: ledgerBalanceAt(a.ledger_account_id, end),
      total_in: txs.reduce((s, t) => s + Math.max(t.amount as number, 0), 0), total_out: txs.reduce((s, t) => s + Math.min(t.amount as number, 0), 0),
      transactions: txs,
    };
    if ((req.query as { format?: string }).format === 'csv') {
      reply.header('content-type', 'text/csv').header('content-disposition', `attachment; filename="statement-${a.account_number.slice(-4)}-${month}.csv"`);
      return csv(txs);
    }
    return statement;
  });

  // ----- Transactions -----
  app.get('/transactions', async (req, reply) => {
    const q = z.object({
      account_id: z.string().optional(), status: z.string().optional(), kind: z.string().optional(), search: z.string().optional(),
      from: z.string().optional(), to: z.string().optional(), card_id: z.string().optional(), uncategorized: z.string().optional(),
      direction: z.enum(['in', 'out']).optional(), limit: z.coerce.number().int().min(1).max(5000).default(100),
      offset: z.coerce.number().int().min(0).default(0), format: z.string().optional(),
    }).parse(req.query);
    const where = ['t.org_id = ?'];
    const p: (string | number)[] = [req.actor.org_id];
    if (req.actor.role === 'employee') { where.push('c.user_id = ?'); p.push(req.actor.id); }
    if (q.account_id) { where.push('t.account_id = ?'); p.push(q.account_id); }
    if (q.status) { where.push('t.status = ?'); p.push(q.status); }
    if (q.kind) { where.push('t.kind = ?'); p.push(q.kind); }
    if (q.card_id) { where.push('t.card_id = ?'); p.push(q.card_id); }
    if (q.direction) where.push(q.direction === 'in' ? 't.amount > 0' : 't.amount < 0');
    if (q.from) { where.push('t.created_at >= ?'); p.push(q.from); }
    if (q.to) { where.push('t.created_at < ?'); p.push(q.to + 'T99'); }
    if (q.uncategorized === 'true') where.push("g.system_key IN ('uncategorized_expense','uncategorized_income')");
    if (q.search) { where.push('(t.counterparty_name LIKE ? OR t.description LIKE ? OR t.note LIKE ?)'); p.push(`%${q.search}%`, `%${q.search}%`, `%${q.search}%`); }
    const sql = `${TX_SELECT} WHERE ${where.join(' AND ')} ORDER BY t.created_at DESC, t.id`;
    const total = one<{ n: number }>(`SELECT COUNT(*) AS n FROM (${sql})`, ...p)!.n;
    const rows = all<Record<string, unknown>>(`${sql} LIMIT ? OFFSET ?`, ...p, q.limit, q.offset);
    if (q.format === 'csv') {
      reply.header('content-type', 'text/csv').header('content-disposition', 'attachment; filename="transactions.csv"');
      return csv(rows.map((r) => ({ date: r.created_at, posted: r.posted_at, account: r.account_name, counterparty: r.counterparty_name, description: r.description, amount: (r.amount as number) / 100, status: r.status, category: r.category_name, note: r.note })));
    }
    return { total, rows };
  });

  app.get('/transactions/:id', async (req) => {
    const t = one<{ card_id: string | null }>(`${TX_SELECT} WHERE t.id = ? AND t.org_id = ?`, (req.params as { id: string }).id, req.actor.org_id);
    if (!t || (req.actor.role === 'employee' && !ownsCard(req.actor.id, t.card_id))) throw notFound('Transaction');
    return t;
  });

  app.patch('/transactions/:id', async (req) => {
    const id = (req.params as { id: string }).id;
    const body = z.object({
      note: z.string().nullable().optional(), receipt_name: z.string().nullable().optional(),
      gl_account_id: z.string().optional(), reconciled: z.boolean().optional(),
    }).parse(req.body);
    const t = one<{ id: string; status: string; card_id: string | null }>('SELECT * FROM transactions WHERE id = ? AND org_id = ?', id, req.actor.org_id);
    if (!t || (req.actor.role === 'employee' && !ownsCard(req.actor.id, t.card_id))) throw notFound('Transaction');
    if (body.note !== undefined) run('UPDATE transactions SET note = ? WHERE id = ?', body.note, id);
    if (body.receipt_name !== undefined) run('UPDATE transactions SET receipt_name = ? WHERE id = ?', body.receipt_name, id);
    if (body.reconciled !== undefined) { assertRole(req.actor, 'admin', 'bookkeeper'); run('UPDATE transactions SET reconciled = ? WHERE id = ?', body.reconciled ? 1 : 0, id); }
    if (body.gl_account_id) {
      assertRole(req.actor, 'admin', 'bookkeeper');
      if (t.status === 'posted') recategorize(req.actor.org_id, id, body.gl_account_id);
      else run('UPDATE transactions SET gl_account_id = ? WHERE id = ?', body.gl_account_id, id);
    }
    return one(`${TX_SELECT} WHERE t.id = ?`, id);
  });

  // ----- Recipients -----
  const recipient = z.object({
    name: z.string().min(1), email: z.string().email().nullable().optional(), type: z.enum(['business', 'individual']).default('business'),
    ach_routing: z.string().regex(/^\d{9}$/).nullable().optional(), ach_account: z.string().regex(/^\d{4,17}$/).nullable().optional(),
    wire_routing: z.string().regex(/^\d{9}$/).nullable().optional(), wire_account: z.string().regex(/^\d{4,17}$/).nullable().optional(),
    swift: z.string().regex(/^[A-Z0-9]{8,11}$/).nullable().optional(), iban: z.string().min(10).max(34).nullable().optional(),
    country: z.string().length(2).default('US'), address: z.string().nullable().optional(), default_gl_account_id: z.string().nullable().optional(),
    is_customer: z.boolean().optional(), is_vendor: z.boolean().optional(),
  });

  app.get('/recipients', async (req) => {
    assertRole(req.actor, 'admin', 'bookkeeper');
    return all('SELECT * FROM counterparties WHERE org_id = ? ORDER BY name', req.actor.org_id);
  });

  app.post('/recipients', async (req) => {
    assertRole(req.actor, 'admin', 'bookkeeper');
    const b = recipient.parse(req.body);
    const id = newId('cp');
    run(
      `INSERT INTO counterparties(id, org_id, name, email, type, ach_routing, ach_account, wire_routing, wire_account, swift, iban, country, address, default_gl_account_id, is_customer, is_vendor, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      id, req.actor.org_id, b.name, b.email ?? null, b.type, b.ach_routing ?? null, b.ach_account ?? null, b.wire_routing ?? null, b.wire_account ?? null,
      b.swift ?? null, b.iban ?? null, b.country, b.address ?? null, b.default_gl_account_id ?? null, b.is_customer ? 1 : 0, b.is_vendor ? 1 : 0, nowIso(),
    );
    audit(req.actor, 'recipient.created', 'counterparty', id, { name: b.name });
    return one('SELECT * FROM counterparties WHERE id = ?', id);
  });

  app.patch('/recipients/:id', async (req) => {
    assertRole(req.actor, 'admin', 'bookkeeper');
    const id = (req.params as { id: string }).id;
    const cur = one<Record<string, unknown>>('SELECT * FROM counterparties WHERE id = ? AND org_id = ?', id, req.actor.org_id);
    if (!cur) throw notFound('Recipient');
    const b = recipient.partial().parse(req.body);
    const m = { ...cur, ...b } as Record<string, string | number | null>;
    run(
      `UPDATE counterparties SET name = ?, email = ?, type = ?, ach_routing = ?, ach_account = ?, wire_routing = ?, wire_account = ?, swift = ?, iban = ?,
         country = ?, address = ?, default_gl_account_id = ?, is_customer = ?, is_vendor = ? WHERE id = ?`,
      m.name, m.email ?? null, m.type, m.ach_routing ?? null, m.ach_account ?? null, m.wire_routing ?? null, m.wire_account ?? null, m.swift ?? null,
      m.iban ?? null, m.country, m.address ?? null, m.default_gl_account_id ?? null, Number(m.is_customer) ? 1 : 0, Number(m.is_vendor) ? 1 : 0, id,
    );
    return one('SELECT * FROM counterparties WHERE id = ?', id);
  });

  app.delete('/recipients/:id', async (req) => {
    assertRole(req.actor, 'admin');
    const id = (req.params as { id: string }).id;
    const used = one('SELECT 1 FROM payments WHERE counterparty_id = ? UNION SELECT 1 FROM bills WHERE vendor_id = ? UNION SELECT 1 FROM invoices WHERE customer_id = ?', id, id, id);
    if (used) throw bad('Recipient has payment history and cannot be deleted');
    run('DELETE FROM counterparties WHERE id = ? AND org_id = ?', id, req.actor.org_id);
    return { ok: true };
  });

  // ----- Payments -----
  const PAY_SELECT = `SELECT p.*, a.name AS account_name, c.name AS counterparty_name, ta.name AS to_account_name, u.name AS created_by_name, ap.name AS approved_by_name
    FROM payments p JOIN accounts a ON a.id = p.account_id LEFT JOIN counterparties c ON c.id = p.counterparty_id
    LEFT JOIN accounts ta ON ta.id = p.to_account_id LEFT JOIN users u ON u.id = p.created_by LEFT JOIN users ap ON ap.id = p.approved_by`;

  app.get('/payments', async (req) => {
    assertRole(req.actor, 'admin', 'bookkeeper');
    const q = req.query as { status?: string };
    return q.status
      ? all(`${PAY_SELECT} WHERE p.org_id = ? AND p.status = ? ORDER BY p.created_at DESC LIMIT 500`, req.actor.org_id, q.status)
      : all(`${PAY_SELECT} WHERE p.org_id = ? ORDER BY p.created_at DESC LIMIT 500`, req.actor.org_id);
  });

  app.get('/payments/:id', async (req) => {
    assertRole(req.actor, 'admin', 'bookkeeper');
    const p = one(`${PAY_SELECT} WHERE p.id = ? AND p.org_id = ?`, (req.params as { id: string }).id, req.actor.org_id);
    if (!p) throw notFound('Payment');
    return p;
  });

  app.post('/payments', async (req) => {
    const b = z.object({
      account_id: z.string(), counterparty_id: z.string().nullable().optional(), to_account_id: z.string().nullable().optional(),
      rail, amount: cents, memo: z.string().max(140).nullable().optional(), scheduled_for: date.nullable().optional(),
      gl_account_id: z.string().nullable().optional(),
    }).parse(req.body);
    const key = req.headers['idempotency-key'];
    return createPayment(req.actor, { ...b, idempotency_key: typeof key === 'string' ? key : null });
  });

  app.post('/payments/:id/approve', async (req) => approvePayment(req.actor, (req.params as { id: string }).id));
  app.post('/payments/:id/cancel', async (req) => cancelPayment(req.actor, (req.params as { id: string }).id));
}
