import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { all, bad, newId, notFound, one, run } from '../db.js';
import { nowIso, today } from '../clock.js';
import { createApiKey, hashPassword, logout } from '../auth.js';
import { assertRole, balances, listAccounts } from '../services/banking.js';
import { audit, listAudit } from '../services/audit.js';
import type { Account } from '../services/banking.js';

export default async function orgRoutes(app: FastifyInstance) {
  app.get('/me', async (req) => {
    const u = one('SELECT id, org_id, email, name, role, status, created_at FROM users WHERE id = ?', req.actor.id);
    const org = one('SELECT * FROM organizations WHERE id = ?', req.actor.org_id);
    return { user: u, organization: org };
  });

  app.post('/auth/logout', async (req) => {
    logout((req.headers.authorization ?? '').slice(7));
    return { ok: true };
  });

  app.patch('/organization', async (req) => {
    assertRole(req.actor, 'admin');
    const b = z.object({ name: z.string().min(1).optional(), address: z.string().min(1).optional(), approval_threshold: z.number().int().positive().optional() }).parse(req.body);
    const o = one<{ name: string; address: string; approval_threshold: number }>('SELECT * FROM organizations WHERE id = ?', req.actor.org_id)!;
    run('UPDATE organizations SET name = ?, address = ?, approval_threshold = ? WHERE id = ?', b.name ?? o.name, b.address ?? o.address, b.approval_threshold ?? o.approval_threshold, req.actor.org_id);
    audit(req.actor, 'organization.updated', 'organization', req.actor.org_id, b);
    return one('SELECT * FROM organizations WHERE id = ?', req.actor.org_id);
  });

  // ----- Team -----
  app.get('/team', async (req) => all(
    `SELECT u.id, u.email, u.name, u.role, u.status, u.created_at,
       (SELECT COUNT(*) FROM cards c WHERE c.user_id = u.id AND c.status != 'canceled') AS active_cards
     FROM users u WHERE u.org_id = ? ORDER BY u.created_at`, req.actor.org_id,
  ));

  app.post('/team', async (req) => {
    assertRole(req.actor, 'admin');
    const b = z.object({ email: z.string().email(), name: z.string().min(1), role: z.enum(['admin', 'bookkeeper', 'employee']), password: z.string().min(8).optional() }).parse(req.body);
    if (one('SELECT 1 FROM users WHERE email = ?', b.email.toLowerCase())) throw bad('A user with that email already exists');
    const id = newId('user');
    run('INSERT INTO users(id, org_id, email, name, role, password_hash, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
      id, req.actor.org_id, b.email.toLowerCase(), b.name, b.role, hashPassword(b.password ?? 'password123'), 'active', nowIso());
    audit(req.actor, 'user.invited', 'user', id, { email: b.email, role: b.role });
    return one('SELECT id, email, name, role, status, created_at FROM users WHERE id = ?', id);
  });

  app.patch('/team/:id', async (req) => {
    assertRole(req.actor, 'admin');
    const id = (req.params as { id: string }).id;
    const u = one<{ role: string; status: string }>('SELECT * FROM users WHERE id = ? AND org_id = ?', id, req.actor.org_id);
    if (!u) throw notFound('User');
    const b = z.object({ role: z.enum(['admin', 'bookkeeper', 'employee']).optional(), status: z.enum(['active', 'disabled']).optional() }).parse(req.body);
    if (id === req.actor.id && (b.role && b.role !== 'admin' || b.status === 'disabled')) throw bad('You cannot demote or disable yourself');
    run('UPDATE users SET role = ?, status = ? WHERE id = ?', b.role ?? u.role, b.status ?? u.status, id);
    if (b.status === 'disabled') {
      run('DELETE FROM sessions WHERE user_id = ?', id);
      run("UPDATE cards SET status = 'frozen' WHERE user_id = ? AND status = 'active'", id);
    }
    audit(req.actor, 'user.updated', 'user', id, b);
    return one('SELECT id, email, name, role, status, created_at FROM users WHERE id = ?', id);
  });

  // ----- API keys -----
  app.get('/api-keys', async (req) => {
    assertRole(req.actor, 'admin');
    return all('SELECT id, name, prefix, created_at, revoked_at FROM api_keys WHERE org_id = ? ORDER BY created_at DESC', req.actor.org_id);
  });
  app.post('/api-keys', async (req) => {
    assertRole(req.actor, 'admin');
    const b = z.object({ name: z.string().min(1) }).parse(req.body);
    const k = createApiKey(req.actor, b.name);
    audit(req.actor, 'api_key.created', 'api_key', k.id, { name: b.name });
    return k;
  });
  app.delete('/api-keys/:id', async (req) => {
    assertRole(req.actor, 'admin');
    run('UPDATE api_keys SET revoked_at = ? WHERE id = ? AND org_id = ?', nowIso(), (req.params as { id: string }).id, req.actor.org_id);
    return { ok: true };
  });

  app.get('/audit', async (req) => {
    assertRole(req.actor, 'admin');
    return listAudit(req.actor.org_id);
  });

  // ----- Insights (dashboard) -----
  app.get('/insights', async (req) => {
    const org = req.actor.org_id;
    const accounts = listAccounts(org);
    const cash = accounts.filter((a) => a.type !== 'credit').reduce((s, a) => s + a.balance.current, 0);
    const creditOwed = accounts.filter((a) => a.type === 'credit').reduce((s, a) => s - a.balance.current, 0);
    const t = today();
    const start = new Date(t.slice(0, 7) + '-01T00:00:00Z');
    start.setUTCMonth(start.getUTCMonth() - 5);
    const monthly = all<{ month: string; money_in: number; money_out: number }>(
      `SELECT substr(posted_at, 1, 7) AS month,
          SUM(CASE WHEN amount > 0 THEN amount ELSE 0 END) AS money_in,
          -SUM(CASE WHEN amount < 0 THEN amount ELSE 0 END) AS money_out
       FROM transactions t JOIN accounts a ON a.id = t.account_id
       WHERE t.org_id = ? AND t.status = 'posted' AND t.transfer_group IS NULL AND a.type != 'credit' AND t.posted_at >= ?
       GROUP BY month ORDER BY month`, org, start.toISOString(),
    );
    const complete = monthly.filter((m) => m.month < t.slice(0, 7)).slice(-3);
    const burn = complete.length ? Math.round(complete.reduce((s, m) => s + (m.money_out - m.money_in), 0) / complete.length) : 0;
    const since30 = new Date(Date.parse(t) - 30 * 86_400_000).toISOString();
    const last30 = one<{ money_in: number; money_out: number }>(
      `SELECT COALESCE(SUM(CASE WHEN amount > 0 THEN amount END), 0) AS money_in, COALESCE(-SUM(CASE WHEN amount < 0 THEN amount END), 0) AS money_out
       FROM transactions WHERE org_id = ? AND status = 'posted' AND transfer_group IS NULL AND kind NOT IN ('cashback') AND posted_at >= ?`, org, since30,
    )!;
    const topSpend = all<{ name: string; amount: number }>(
      `SELECT g.name, SUM(l.amount) AS amount FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id JOIN gl_accounts g ON g.id = l.gl_account_id
       WHERE e.org_id = ? AND g.type = 'expense' AND e.date >= ? GROUP BY g.id HAVING amount > 0 ORDER BY amount DESC LIMIT 6`, org, since30.slice(0, 10),
    );
    return {
      as_of: t,
      total_cash: cash,
      credit_owed: creditOwed,
      accounts: accounts.map((a: Account & { balance: ReturnType<typeof balances> }) => ({ id: a.id, name: a.name, type: a.type, last4: a.account_number.slice(-4), balance: a.balance, apy_bps: a.apy_bps, credit_limit: a.credit_limit })),
      last_30_days: last30,
      monthly_cashflow: monthly,
      avg_monthly_burn: burn,
      runway_months: burn > 0 ? Math.round((cash / burn) * 10) / 10 : null,
      top_spend_categories: topSpend,
      pending_approvals: one<{ n: number }>("SELECT COUNT(*) AS n FROM payments WHERE org_id = ? AND status = 'pending_approval'", org)!.n,
      bills_due_7d: one<{ n: number; s: number }>("SELECT COUNT(*) AS n, COALESCE(SUM(amount), 0) AS s FROM bills WHERE org_id = ? AND status IN ('approved','pending_approval') AND due_date <= date(?, '+7 days')", org, t)!,
      overdue_invoices: one<{ n: number; s: number }>("SELECT COUNT(*) AS n, COALESCE(SUM(total - amount_paid), 0) AS s FROM invoices WHERE org_id = ? AND status = 'sent' AND due_date < ?", org, t)!,
      reimbursements_to_review: one<{ n: number }>("SELECT COUNT(*) AS n FROM reimbursements WHERE org_id = ? AND status = 'submitted'", org)!.n,
    };
  });
}
