import { AppError, all, bad, newId, notFound, one, run, tx } from './db.js';
import { nowIso } from './clock.js';

// Books: the company's own accrual general ledger. Debits are positive, credits negative;
// every journal entry sums to zero. Bank activity posts here automatically.

export type GlType = 'asset' | 'liability' | 'equity' | 'revenue' | 'expense';

export interface GlAccount {
  id: string;
  org_id: string;
  code: string;
  name: string;
  type: GlType;
  subtype: string | null;
  system_key: string | null;
  archived: number;
}

const DEFAULT_COA: [string, string, GlType, string | null][] = [
  ['1200', 'Accounts Receivable', 'asset', 'ar'],
  ['1300', 'Prepaid Expenses', 'asset', null],
  ['1500', 'Computer Equipment', 'asset', 'equipment'],
  ['2000', 'Accounts Payable', 'liability', 'ap'],
  ['2200', 'Accrued Liabilities', 'liability', null],
  ['2300', 'Payroll Liabilities', 'liability', null],
  ['3000', 'Common Stock & APIC', 'equity', 'capital'],
  ['3100', 'Retained Earnings', 'equity', 'retained'],
  ['4000', 'Subscription Revenue', 'revenue', 'revenue'],
  ['4100', 'Services Revenue', 'revenue', 'services_revenue'],
  ['4900', 'Interest Income', 'revenue', 'interest_income'],
  ['4910', 'Cashback Rewards', 'revenue', 'rewards'],
  ['4999', 'Uncategorized Income', 'revenue', 'uncategorized_income'],
  ['5000', 'Cloud Hosting (COGS)', 'expense', 'cloud'],
  ['6000', 'Payroll', 'expense', 'payroll'],
  ['6100', 'Rent & Facilities', 'expense', 'rent'],
  ['6200', 'Software & Subscriptions', 'expense', 'software'],
  ['6300', 'Advertising & Marketing', 'expense', 'advertising'],
  ['6400', 'Travel', 'expense', 'travel'],
  ['6450', 'Meals & Entertainment', 'expense', 'meals'],
  ['6500', 'Office Supplies', 'expense', 'office'],
  ['6600', 'Legal & Professional Services', 'expense', 'professional_services'],
  ['6700', 'Bank Fees', 'expense', 'fees'],
  ['6800', 'Contractors', 'expense', 'contractors'],
  ['6900', 'Insurance', 'expense', 'insurance'],
  ['6950', 'Utilities & Telecom', 'expense', 'utilities'],
  ['6990', 'Taxes & Licenses', 'expense', 'taxes'],
  ['9999', 'Uncategorized Expense', 'expense', 'uncategorized_expense'],
];

/** Merchant categories reported by the card network / payment description, mapped to GL keys. */
export const MERCHANT_CATEGORIES = [
  'cloud', 'software', 'advertising', 'travel', 'meals', 'office', 'utilities', 'payroll', 'rent',
  'insurance', 'professional_services', 'contractors', 'taxes', 'fees', 'equipment',
] as const;

export function seedChartOfAccounts(orgId: string) {
  for (const [code, name, type, key] of DEFAULT_COA) {
    run(
      'INSERT OR IGNORE INTO gl_accounts(id, org_id, code, name, type, system_key) VALUES (?, ?, ?, ?, ?, ?)',
      newId('gl'), orgId, code, name, type, key,
    );
  }
}

export function glByKey(orgId: string, key: string): GlAccount {
  const g = one<GlAccount>('SELECT * FROM gl_accounts WHERE org_id = ? AND system_key = ?', orgId, key);
  if (!g) throw new Error(`Missing GL account ${key}`);
  return g;
}

export function getGl(orgId: string, id: string): GlAccount {
  const g = one<GlAccount>('SELECT * FROM gl_accounts WHERE org_id = ? AND id = ?', orgId, id);
  if (!g) throw notFound('GL account');
  return g;
}

/** Each bank account gets its own GL account: an asset for deposits, a liability for the credit card. */
export function createBankGlAccount(orgId: string, bankAccountId: string, name: string, isCredit: boolean): string {
  const base = isCredit ? 2100 : 1000;
  const used = all<{ code: string }>('SELECT code FROM gl_accounts WHERE org_id = ? AND code >= ? AND code < ?', orgId, String(base), String(base + 100)).map((r) => r.code);
  let code = base;
  while (used.includes(String(code))) code++;
  const id = newId('gl');
  run(
    'INSERT INTO gl_accounts(id, org_id, code, name, type, subtype, system_key) VALUES (?, ?, ?, ?, ?, ?, ?)',
    id, orgId, String(code), name, isCredit ? 'liability' : 'asset', isCredit ? 'credit_card' : 'bank', `bank:${bankAccountId}`,
  );
  return id;
}

function assertOpenPeriod(orgId: string, date: string) {
  const org = one<{ books_locked_through: string | null }>('SELECT books_locked_through FROM organizations WHERE id = ?', orgId);
  if (org?.books_locked_through && date <= org.books_locked_through) {
    throw new AppError(409, `Books are closed through ${org.books_locked_through}`, 'period_locked');
  }
}

export interface JournalLineInput {
  gl_account_id: string;
  amount: number;
  description?: string;
}

export function postJournal(
  orgId: string,
  input: { date: string; memo: string; source_type: string; source_id?: string | null; created_by?: string | null; lines: JournalLineInput[] },
): string {
  const lines = input.lines.filter((l) => l.amount !== 0);
  if (lines.length < 2) throw bad('A journal entry needs at least two non-zero lines');
  if (lines.some((l) => !Number.isInteger(l.amount))) throw bad('Amounts must be integer cents');
  const sum = lines.reduce((s, l) => s + l.amount, 0);
  if (sum !== 0) throw bad(`Debits and credits must balance (off by ${sum})`);
  assertOpenPeriod(orgId, input.date);
  return tx(() => {
    for (const l of lines) getGl(orgId, l.gl_account_id);
    const id = newId('je');
    run(
      'INSERT INTO journal_entries(id, org_id, date, memo, source_type, source_id, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
      id, orgId, input.date, input.memo, input.source_type, input.source_id ?? null, input.created_by ?? null, nowIso(),
    );
    for (const l of lines) {
      run('INSERT INTO journal_lines(entry_id, gl_account_id, amount, description) VALUES (?, ?, ?, ?)', id, l.gl_account_id, l.amount, l.description ?? null);
    }
    return id;
  });
}

export function deleteJournalsFor(orgId: string, sourceType: string, sourceId: string) {
  const entries = all<{ id: string; date: string }>('SELECT id, date FROM journal_entries WHERE org_id = ? AND source_type = ? AND source_id = ?', orgId, sourceType, sourceId);
  for (const e of entries) {
    assertOpenPeriod(orgId, e.date);
    run('DELETE FROM journal_lines WHERE entry_id = ?', e.id);
    run('DELETE FROM journal_entries WHERE id = ?', e.id);
  }
}

interface TxRow {
  id: string;
  org_id: string;
  account_id: string;
  amount: number;
  status: string;
  kind: string;
  counterparty_name: string;
  description: string;
  merchant_category: string | null;
  gl_account_id: string | null;
  transfer_group: string | null;
  posted_at: string | null;
}

const KIND_DEFAULTS: Record<string, string> = {
  interest: 'interest_income',
  cashback: 'rewards',
  fee: 'fees',
};

/** Suggests a GL account for a transaction: explicit > rules > counterparty default > merchant category > kind > uncategorized. */
export function suggestCategory(t: Pick<TxRow, 'org_id' | 'amount' | 'kind' | 'counterparty_name' | 'description' | 'merchant_category' | 'gl_account_id'>): string {
  if (t.gl_account_id) return t.gl_account_id;
  const rules = all<{ match_field: string; match_value: string; gl_account_id: string }>(
    'SELECT * FROM categorization_rules WHERE org_id = ? ORDER BY priority ASC, created_at ASC', t.org_id,
  );
  for (const r of rules) {
    const hay = (r.match_field === 'counterparty' ? t.counterparty_name : r.match_field === 'description' ? t.description : t.merchant_category ?? '').toLowerCase();
    if (hay.includes(r.match_value.toLowerCase())) return r.gl_account_id;
  }
  const cp = one<{ default_gl_account_id: string | null }>(
    'SELECT default_gl_account_id FROM counterparties WHERE org_id = ? AND name = ? AND default_gl_account_id IS NOT NULL', t.org_id, t.counterparty_name,
  );
  if (cp?.default_gl_account_id) return cp.default_gl_account_id;
  if (t.merchant_category) {
    const g = one<{ id: string }>('SELECT id FROM gl_accounts WHERE org_id = ? AND system_key = ?', t.org_id, t.merchant_category);
    if (g) return g.id;
  }
  const kindKey = KIND_DEFAULTS[t.kind];
  if (kindKey) return glByKey(t.org_id, kindKey).id;
  return glByKey(t.org_id, t.amount >= 0 ? 'uncategorized_income' : 'uncategorized_expense').id;
}

function bankGl(orgId: string, accountId: string): string {
  return one<{ gl_account_id: string }>('SELECT gl_account_id FROM accounts WHERE id = ?', accountId)!.gl_account_id;
}

/** Posts a settled bank transaction into the books. Idempotent per transaction / transfer group. */
export function bookTransaction(txId: string) {
  const t = one<TxRow>('SELECT * FROM transactions WHERE id = ?', txId);
  if (!t || t.status !== 'posted' || !t.posted_at) return;
  const date = t.posted_at.slice(0, 10);
  if (t.transfer_group) {
    if (one('SELECT 1 FROM journal_entries WHERE source_type = ? AND source_id = ?', 'transfer', t.transfer_group)) return;
    const legs = all<TxRow>("SELECT * FROM transactions WHERE transfer_group = ? AND status = 'posted'", t.transfer_group);
    if (legs.length < 2) return;
    postJournal(t.org_id, {
      date, memo: t.description, source_type: 'transfer', source_id: t.transfer_group,
      lines: legs.map((l) => ({ gl_account_id: bankGl(t.org_id, l.account_id), amount: l.amount })),
    });
    return;
  }
  if (one('SELECT 1 FROM journal_entries WHERE source_type = ? AND source_id = ?', 'transaction', t.id)) return;
  const category = t.gl_account_id ?? suggestCategory(t);
  if (!t.gl_account_id) run('UPDATE transactions SET gl_account_id = ? WHERE id = ?', category, t.id);
  postJournal(t.org_id, {
    date, memo: `${t.counterparty_name} — ${t.description}`, source_type: 'transaction', source_id: t.id,
    lines: [
      { gl_account_id: bankGl(t.org_id, t.account_id), amount: t.amount },
      { gl_account_id: category, amount: -t.amount },
    ],
  });
}

export function recategorize(orgId: string, txId: string, glAccountId: string) {
  getGl(orgId, glAccountId);
  tx(() => {
    const t = one<TxRow>('SELECT * FROM transactions WHERE id = ? AND org_id = ?', txId, orgId);
    if (!t) throw notFound('Transaction');
    if (t.transfer_group) throw bad('Internal transfers are categorized automatically');
    deleteJournalsFor(orgId, 'transaction', txId);
    run('UPDATE transactions SET gl_account_id = ? WHERE id = ?', glAccountId, txId);
    bookTransaction(txId);
  });
}

// ---------- Reports ----------

export function glBalances(orgId: string, opts: { from?: string; to?: string } = {}) {
  return all<GlAccount & { balance: number; debits: number; credits: number }>(
    `SELECT g.*, COALESCE(SUM(l.amount), 0) AS balance,
            COALESCE(SUM(CASE WHEN l.amount > 0 THEN l.amount END), 0) AS debits,
            COALESCE(-SUM(CASE WHEN l.amount < 0 THEN l.amount END), 0) AS credits
     FROM gl_accounts g
     LEFT JOIN journal_lines l ON l.gl_account_id = g.id
       AND l.entry_id IN (SELECT id FROM journal_entries WHERE org_id = ? AND date >= ? AND date <= ?)
     WHERE g.org_id = ?
     GROUP BY g.id ORDER BY g.code`,
    orgId, opts.from ?? '0000-00-00', opts.to ?? '9999-12-31', orgId,
  );
}

/** Natural-sign balance: positive for normal balances (debit for assets/expenses, credit for the rest). */
export const natural = (type: GlType, debitBalance: number) => (type === 'asset' || type === 'expense' ? debitBalance : -debitBalance);

export function trialBalance(orgId: string, asOf: string) {
  const rows = glBalances(orgId, { to: asOf }).filter((r) => r.debits || r.credits);
  const debit = rows.reduce((s, r) => s + Math.max(r.balance, 0), 0);
  const credit = rows.reduce((s, r) => s + Math.max(-r.balance, 0), 0);
  return { as_of: asOf, rows: rows.map((r) => ({ id: r.id, code: r.code, name: r.name, type: r.type, debit: Math.max(r.balance, 0), credit: Math.max(-r.balance, 0) })), total_debit: debit, total_credit: credit, balanced: debit === credit };
}

export function profitAndLoss(orgId: string, from: string, to: string) {
  const rows = glBalances(orgId, { from, to }).filter((r) => (r.type === 'revenue' || r.type === 'expense') && r.balance !== 0);
  const section = (type: GlType) => {
    const lines = rows.filter((r) => r.type === type).map((r) => ({ id: r.id, code: r.code, name: r.name, amount: natural(type, r.balance) }));
    return { lines, total: lines.reduce((s, l) => s + l.amount, 0) };
  };
  const revenue = section('revenue');
  const expenses = section('expense');
  return { from, to, revenue, expenses, net_income: revenue.total - expenses.total };
}

export function balanceSheet(orgId: string, asOf: string) {
  const rows = glBalances(orgId, { to: asOf });
  const section = (type: GlType) => {
    const lines = rows.filter((r) => r.type === type && r.balance !== 0).map((r) => ({ id: r.id, code: r.code, name: r.name, amount: natural(type, r.balance) }));
    return { lines, total: lines.reduce((s, l) => s + l.amount, 0) };
  };
  const assets = section('asset');
  const liabilities = section('liability');
  const equity = section('equity');
  const netIncome = rows.filter((r) => r.type === 'revenue' || r.type === 'expense').reduce((s, r) => s - r.balance, 0);
  equity.lines.push({ id: 'net_income', code: '—', name: 'Net Income (to date)', amount: netIncome });
  equity.total += netIncome;
  return { as_of: asOf, assets, liabilities, equity, balanced: assets.total === liabilities.total + equity.total };
}

export function generalLedger(orgId: string, glAccountId: string, from = '0000-00-00', to = '9999-12-31') {
  const gl = getGl(orgId, glAccountId);
  const opening = one<{ b: number }>(
    `SELECT COALESCE(SUM(l.amount), 0) AS b FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
     WHERE l.gl_account_id = ? AND e.date < ?`, glAccountId, from,
  )!.b;
  const lines = all<{ entry_id: string; date: string; memo: string; source_type: string; source_id: string | null; amount: number; description: string | null }>(
    `SELECT e.id AS entry_id, e.date, e.memo, e.source_type, e.source_id, l.amount, l.description
     FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
     WHERE l.gl_account_id = ? AND e.date >= ? AND e.date <= ? ORDER BY e.date, e.created_at`, glAccountId, from, to,
  );
  let running = opening;
  return {
    account: gl, from, to, opening_balance: natural(gl.type, opening),
    lines: lines.map((l) => {
      running += l.amount;
      return { ...l, debit: Math.max(l.amount, 0), credit: Math.max(-l.amount, 0), balance: natural(gl.type, running) };
    }),
    closing_balance: natural(gl.type, running),
  };
}
