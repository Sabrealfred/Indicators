import { all, one, run, tx } from '../db.js';
import { today } from '../clock.js';
import { SYSTEM } from '../ledger.js';
import { Account, balances, insertTx, postTx, processPayments } from './banking.js';
import { captureStaleAuthorizations, creditMonthEnd } from './cards.js';

// The back office: everything a bank does on a schedule. Runs on an interval in the server,
// on demand from the simulator, and day-by-day while seeding history.

const MICROS = 1_000_000;

function nextDay(d: string) {
  const x = new Date(d + 'T00:00:00Z');
  x.setUTCDate(x.getUTCDate() + 1);
  return x.toISOString().slice(0, 10);
}

/** Daily accrual on interest-bearing accounts, paid out on the first of each month. */
export function accrueInterest() {
  const t = today();
  for (const a of all<Account>("SELECT * FROM accounts WHERE apy_bps > 0 AND status = 'open'")) {
    tx(() => {
      let day = a.last_accrual_date ?? t;
      let accrued = a.accrued_interest_micros;
      while (day < t) {
        day = nextDay(day);
        if (day.endsWith('-01') && accrued >= MICROS) {
          const cents = Math.floor(accrued / MICROS);
          accrued -= cents * MICROS;
          const label = new Date(day + 'T00:00:00Z');
          label.setUTCDate(0);
          const id = insertTx({
            org_id: a.org_id, account_id: a.id, amount: cents, status: 'pending', kind: 'interest',
            counterparty_name: a.type === 'treasury' ? 'Treasury yield' : 'Interest', description: `Interest for ${label.toISOString().slice(0, 7)}`,
          });
          postTx(id, SYSTEM.interest);
        }
        const bal = Math.max(balances(a).current, 0);
        accrued += Math.floor((bal * a.apy_bps * MICROS) / 10_000 / 365);
      }
      run('UPDATE accounts SET accrued_interest_micros = ?, last_accrual_date = ? WHERE id = ?', accrued, day, a.id);
    });
  }
}

function monthEnds() {
  const month = today().slice(0, 7);
  for (const org of all<{ id: string }>('SELECT id FROM organizations')) {
    const key = `month_end:${org.id}`;
    const last = one<{ value: string }>('SELECT value FROM meta WHERE key = ?', key)?.value;
    if (last === month) continue;
    if (last) {
      const prev = new Date(month + '-01T00:00:00Z');
      prev.setUTCMonth(prev.getUTCMonth() - 1);
      creditMonthEnd(org.id, `${month}-01`, prev.toISOString().slice(0, 10));
    }
    run('INSERT INTO meta(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value', key, month);
  }
}

export function tick() {
  const payments = processPayments();
  const captured = captureStaleAuthorizations();
  accrueInterest();
  monthEnds();
  return { ...payments, captured };
}
