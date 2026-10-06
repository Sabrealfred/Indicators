import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { all, bad, newId, notFound, one, run } from '../db.js';
import { nowIso, today } from '../clock.js';
import { ledgerBalance } from '../ledger.js';
import {
  balanceSheet, bookTransaction, deleteJournalsFor, generalLedger, getGl, glBalances, natural, postJournal, profitAndLoss, suggestCategory, trialBalance,
} from '../books.js';
import { assertRole } from '../services/banking.js';
import { audit } from '../services/audit.js';

const date = z.string().regex(/^\d{4}-\d{2}-\d{2}$/);

export default async function booksRoutes(app: FastifyInstance) {
  app.addHook('preHandler', async (req) => assertRole(req.actor, 'admin', 'bookkeeper'));

  app.get('/books/accounts', async (req) => {
    const q = req.query as { from?: string; to?: string };
    return glBalances(req.actor.org_id, q).map((g) => ({ ...g, balance: natural(g.type, g.balance) }));
  });

  app.post('/books/accounts', async (req) => {
    const b = z.object({
      code: z.string().regex(/^\d{4}$/), name: z.string().min(1), type: z.enum(['asset', 'liability', 'equity', 'revenue', 'expense']), subtype: z.string().nullable().optional(),
    }).parse(req.body);
    if (one('SELECT 1 FROM gl_accounts WHERE org_id = ? AND code = ?', req.actor.org_id, b.code)) throw bad(`Code ${b.code} is already used`);
    const id = newId('gl');
    run('INSERT INTO gl_accounts(id, org_id, code, name, type, subtype) VALUES (?, ?, ?, ?, ?, ?)', id, req.actor.org_id, b.code, b.name, b.type, b.subtype ?? null);
    audit(req.actor, 'gl_account.created', 'gl_account', id, b);
    return getGl(req.actor.org_id, id);
  });

  app.patch('/books/accounts/:id', async (req) => {
    const g = getGl(req.actor.org_id, (req.params as { id: string }).id);
    const b = z.object({ name: z.string().min(1).optional(), archived: z.boolean().optional() }).parse(req.body);
    if (b.archived && g.system_key) throw bad('System accounts cannot be archived');
    run('UPDATE gl_accounts SET name = ?, archived = ? WHERE id = ?', b.name ?? g.name, b.archived === undefined ? g.archived : b.archived ? 1 : 0, g.id);
    return getGl(req.actor.org_id, g.id);
  });

  app.get('/books/accounts/:id/ledger', async (req) => {
    const q = req.query as { from?: string; to?: string };
    return generalLedger(req.actor.org_id, (req.params as { id: string }).id, q.from, q.to);
  });

  app.get('/books/journal', async (req) => {
    const q = z.object({ source_type: z.string().optional(), limit: z.coerce.number().int().max(1000).default(100) }).parse(req.query);
    const entries = q.source_type
      ? all<{ id: string }>('SELECT * FROM journal_entries WHERE org_id = ? AND source_type = ? ORDER BY date DESC, created_at DESC LIMIT ?', req.actor.org_id, q.source_type, q.limit)
      : all<{ id: string }>('SELECT * FROM journal_entries WHERE org_id = ? ORDER BY date DESC, created_at DESC LIMIT ?', req.actor.org_id, q.limit);
    return entries.map((e) => ({
      ...e,
      lines: all('SELECT l.*, g.code, g.name FROM journal_lines l JOIN gl_accounts g ON g.id = l.gl_account_id WHERE l.entry_id = ? ORDER BY l.amount DESC', e.id),
    }));
  });

  app.post('/books/journal', async (req) => {
    const b = z.object({
      date, memo: z.string().min(1),
      lines: z.array(z.object({ gl_account_id: z.string(), debit: z.number().int().min(0).default(0), credit: z.number().int().min(0).default(0), description: z.string().optional() })).min(2),
    }).parse(req.body);
    const id = postJournal(req.actor.org_id, {
      date: b.date, memo: b.memo, source_type: 'manual', created_by: req.actor.id,
      lines: b.lines.map((l) => ({ gl_account_id: l.gl_account_id, amount: l.debit - l.credit, description: l.description })),
    });
    run('UPDATE journal_entries SET source_id = ? WHERE id = ?', id, id);
    audit(req.actor, 'journal.posted', 'journal_entry', id, { memo: b.memo });
    return { id };
  });

  app.delete('/books/journal/:id', async (req) => {
    const e = one<{ id: string; source_type: string }>('SELECT * FROM journal_entries WHERE id = ? AND org_id = ?', (req.params as { id: string }).id, req.actor.org_id);
    if (!e) throw notFound('Journal entry');
    if (e.source_type !== 'manual') throw bad('Only manual entries can be deleted; change the source document instead');
    deleteJournalsFor(req.actor.org_id, 'manual', e.id);
    audit(req.actor, 'journal.deleted', 'journal_entry', e.id);
    return { ok: true };
  });

  // ----- Reports -----
  app.get('/books/reports/pnl', async (req) => {
    const q = req.query as { from?: string; to?: string };
    const t = today();
    return profitAndLoss(req.actor.org_id, q.from ?? `${t.slice(0, 4)}-01-01`, q.to ?? t);
  });

  app.get('/books/reports/pnl-monthly', async (req) => {
    const months = Math.min(Number((req.query as { months?: string }).months ?? 6), 24);
    const out = [];
    const d = new Date(today().slice(0, 7) + '-01T00:00:00Z');
    d.setUTCMonth(d.getUTCMonth() - months + 1);
    for (let i = 0; i < months; i++) {
      const from = d.toISOString().slice(0, 10);
      d.setUTCMonth(d.getUTCMonth() + 1);
      const end = new Date(d.getTime() - 86_400_000).toISOString().slice(0, 10);
      const p = profitAndLoss(req.actor.org_id, from, end);
      out.push({ month: from.slice(0, 7), revenue: p.revenue.total, expenses: p.expenses.total, net_income: p.net_income, expense_lines: p.expenses.lines });
    }
    return out;
  });

  app.get('/books/reports/balance-sheet', async (req) => balanceSheet(req.actor.org_id, (req.query as { as_of?: string }).as_of ?? today()));
  app.get('/books/reports/trial-balance', async (req) => trialBalance(req.actor.org_id, (req.query as { as_of?: string }).as_of ?? today()));

  app.get('/books/reports/ar-aging', async (req) => agingReport(req.actor.org_id, 'ar'));
  app.get('/books/reports/ap-aging', async (req) => agingReport(req.actor.org_id, 'ap'));

  // ----- Categorization -----
  app.get('/books/rules', async (req) => all(
    'SELECT r.*, g.code, g.name AS gl_name FROM categorization_rules r JOIN gl_accounts g ON g.id = r.gl_account_id WHERE r.org_id = ? ORDER BY r.priority, r.created_at', req.actor.org_id,
  ));

  app.post('/books/rules', async (req) => {
    const b = z.object({
      match_field: z.enum(['counterparty', 'description', 'merchant_category']), match_value: z.string().min(1), gl_account_id: z.string(),
      priority: z.number().int().default(100), apply_to_existing: z.boolean().default(false),
    }).parse(req.body);
    getGl(req.actor.org_id, b.gl_account_id);
    const id = newId('rule');
    run('INSERT INTO categorization_rules(id, org_id, match_field, match_value, gl_account_id, priority, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
      id, req.actor.org_id, b.match_field, b.match_value, b.gl_account_id, b.priority, nowIso());
    let applied = 0;
    if (b.apply_to_existing) applied = recategorizeUncategorized(req.actor.org_id);
    return { id, applied };
  });

  app.delete('/books/rules/:id', async (req) => {
    run('DELETE FROM categorization_rules WHERE id = ? AND org_id = ?', (req.params as { id: string }).id, req.actor.org_id);
    return { ok: true };
  });

  app.post('/books/auto-categorize', async (req) => ({ recategorized: recategorizeUncategorized(req.actor.org_id) }));

  // ----- Reconciliation & close -----
  app.get('/books/reconciliation', async (req) => {
    const accts = all<{ id: string; name: string; type: string; ledger_account_id: string; gl_account_id: string }>(
      "SELECT * FROM accounts WHERE org_id = ? AND status != 'closed'", req.actor.org_id,
    );
    return accts.map((a) => {
      const bank = ledgerBalance(a.ledger_account_id);
      const gl = one<{ b: number }>('SELECT COALESCE(SUM(amount), 0) AS b FROM journal_lines WHERE gl_account_id = ?', a.gl_account_id)!.b;
      const unreconciled = one<{ n: number }>("SELECT COUNT(*) AS n FROM transactions WHERE account_id = ? AND status = 'posted' AND reconciled = 0", a.id)!.n;
      return { account_id: a.id, name: a.name, type: a.type, bank_balance: bank, book_balance: gl, difference: bank - gl, unreconciled };
    });
  });

  app.post('/books/reconciliation/:accountId', async (req) => {
    const b = z.object({ through: date }).parse(req.body);
    const r = run(
      "UPDATE transactions SET reconciled = 1 WHERE org_id = ? AND account_id = ? AND status = 'posted' AND posted_at < ?",
      req.actor.org_id, (req.params as { accountId: string }).accountId, b.through + 'T99',
    );
    audit(req.actor, 'books.reconciled', 'account', (req.params as { accountId: string }).accountId, b);
    return { reconciled: Number(r.changes) };
  });

  app.post('/books/close', async (req) => {
    assertRole(req.actor, 'admin');
    const b = z.object({ through: date.nullable() }).parse(req.body);
    if (b.through && b.through >= today()) throw bad('Can only close past periods');
    run('UPDATE organizations SET books_locked_through = ? WHERE id = ?', b.through, req.actor.org_id);
    audit(req.actor, 'books.closed', 'organization', req.actor.org_id, b);
    return { books_locked_through: b.through };
  });
}

function recategorizeUncategorized(orgId: string): number {
  const rows = all<{ id: string; org_id: string; amount: number; kind: string; counterparty_name: string; description: string; merchant_category: string | null; posted_at: string }>(
    `SELECT t.* FROM transactions t JOIN gl_accounts g ON g.id = t.gl_account_id
     WHERE t.org_id = ? AND t.status = 'posted' AND g.system_key IN ('uncategorized_expense','uncategorized_income')`, orgId,
  );
  const locked = one<{ l: string | null }>('SELECT books_locked_through AS l FROM organizations WHERE id = ?', orgId)!.l;
  let n = 0;
  for (const r of rows) {
    if (locked && r.posted_at.slice(0, 10) <= locked) continue;
    const g = suggestCategory({ ...r, gl_account_id: null });
    const cur = one<{ gl_account_id: string }>('SELECT gl_account_id FROM transactions WHERE id = ?', r.id)!.gl_account_id;
    if (g === cur) continue;
    deleteJournalsFor(orgId, 'transaction', r.id);
    run('UPDATE transactions SET gl_account_id = ? WHERE id = ?', g, r.id);
    bookTransaction(r.id);
    n++;
  }
  return n;
}

function agingReport(orgId: string, kind: 'ar' | 'ap') {
  const t = today();
  const rows = kind === 'ar'
    ? all<{ id: string; name: string; ref: string; due_date: string; open: number }>(
      "SELECT i.id, c.name, i.number AS ref, i.due_date, i.total - i.amount_paid AS open FROM invoices i JOIN counterparties c ON c.id = i.customer_id WHERE i.org_id = ? AND i.status = 'sent'", orgId)
    : all<{ id: string; name: string; ref: string; due_date: string; open: number }>(
      "SELECT b.id, c.name, COALESCE(b.invoice_number, b.id) AS ref, b.due_date, b.amount AS open FROM bills b JOIN counterparties c ON c.id = b.vendor_id WHERE b.org_id = ? AND b.status IN ('approved','scheduled')", orgId);
  const bucket = (due: string) => {
    const days = Math.floor((Date.parse(t) - Date.parse(due)) / 86_400_000);
    if (days <= 0) return 'current';
    if (days <= 30) return '1_30';
    if (days <= 60) return '31_60';
    if (days <= 90) return '61_90';
    return 'over_90';
  };
  const totals: Record<string, number> = { current: 0, '1_30': 0, '31_60': 0, '61_90': 0, over_90: 0 };
  const items = rows.map((r) => {
    const b = bucket(r.due_date);
    totals[b] += r.open;
    return { ...r, bucket: b };
  });
  return { as_of: t, items, totals, total: Object.values(totals).reduce((s, v) => s + v, 0) };
}
