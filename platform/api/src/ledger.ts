import { all, bad, newId, one, run, tx } from './db.js';
import { nowIso } from './clock.js';

// Bank core ledger. Balances are never stored; they are the sum of immutable postings.
// System accounts stand in for the outside world (payment rails, card network, the bank itself).

export const SYSTEM = {
  ach: 'la_sys_ach_clearing',
  wire: 'la_sys_wire_clearing',
  card: 'la_sys_card_settlement',
  check: 'la_sys_check_clearing',
  interest: 'la_sys_interest_expense',
  rewards: 'la_sys_rewards_expense',
  fees: 'la_sys_fee_income',
  capital: 'la_sys_opening_capital',
} as const;

export function ensureSystemAccounts() {
  for (const [name, id] of Object.entries(SYSTEM)) {
    run("INSERT OR IGNORE INTO ledger_accounts(id, name, kind) VALUES (?, ?, 'system')", id, name);
  }
}

export function createLedgerAccount(name: string): string {
  const id = newId('la');
  run("INSERT INTO ledger_accounts(id, name, kind) VALUES (?, ?, 'customer')", id, name);
  return id;
}

export interface Posting {
  account: string;
  amount: number;
}

export function postEntry(description: string, postings: Posting[], opts: { idempotencyKey?: string; at?: string } = {}) {
  if (postings.length < 2) throw bad('A ledger entry needs at least two postings');
  if (postings.some((p) => !Number.isInteger(p.amount))) throw bad('Ledger amounts must be integer cents');
  const sum = postings.reduce((s, p) => s + p.amount, 0);
  if (sum !== 0) throw bad(`Ledger entry does not balance (off by ${sum})`);
  return tx(() => {
    if (opts.idempotencyKey) {
      const existing = one<{ id: string }>('SELECT id FROM ledger_entries WHERE idempotency_key = ?', opts.idempotencyKey);
      if (existing) return existing.id;
    }
    const id = newId('le');
    run(
      'INSERT INTO ledger_entries(id, description, idempotency_key, posted_at) VALUES (?, ?, ?, ?)',
      id,
      description,
      opts.idempotencyKey ?? null,
      opts.at ?? nowIso(),
    );
    for (const p of postings) {
      run('INSERT INTO ledger_postings(entry_id, ledger_account_id, amount) VALUES (?, ?, ?)', id, p.account, p.amount);
    }
    return id;
  });
}

export function ledgerBalance(ledgerAccountId: string): number {
  return one<{ b: number }>('SELECT COALESCE(SUM(amount), 0) AS b FROM ledger_postings WHERE ledger_account_id = ?', ledgerAccountId)!.b;
}

export function ledgerBalanceAt(ledgerAccountId: string, iso: string): number {
  return one<{ b: number }>(
    `SELECT COALESCE(SUM(p.amount), 0) AS b FROM ledger_postings p JOIN ledger_entries e ON e.id = p.entry_id
     WHERE p.ledger_account_id = ? AND e.posted_at < ?`,
    ledgerAccountId,
    iso,
  )!.b;
}

/** Invariant check used by tests and the /health endpoint: the whole ledger sums to zero. */
export function trialBalanceIsZero(): boolean {
  return one<{ s: number }>('SELECT COALESCE(SUM(amount), 0) AS s FROM ledger_postings')!.s === 0;
}

export function unbalancedEntries(): string[] {
  return all<{ entry_id: string }>(
    'SELECT entry_id FROM ledger_postings GROUP BY entry_id HAVING SUM(amount) != 0',
  ).map((r) => r.entry_id);
}
