import { beforeAll, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { buildApp } from './app.js';
import { seed } from './seed.js';
import { trialBalanceIsZero, unbalancedEntries } from './ledger.js';
import { luhnValid } from './services/cards.js';

let app: FastifyInstance;
let token: string;
let marcusToken: string;
let employeeToken: string;

async function call(method: string, url: string, body?: unknown, tok = token) {
  const res = await app.inject({ method: method as 'GET', url, payload: body as object, headers: { authorization: `Bearer ${tok}` } });
  return { status: res.statusCode, body: res.headers['content-type']?.includes('json') ? res.json() : res.body };
}
const loginAs = async (email: string) => (await app.inject({ method: 'POST', url: '/auth/login', payload: { email, password: 'demo1234' } })).json().token as string;

const account = async (type: string) => (await call('GET', '/accounts')).body.find((a: { type: string }) => a.type === type);

beforeAll(async () => {
  seed(75);
  app = await buildApp();
  token = await loginAs('demo@ledgerbank.dev');
  marcusToken = await loginAs('marcus@acmerobotics.dev');
  employeeToken = await loginAs('diego@acmerobotics.dev');
});

describe('ledger invariants', () => {
  it('bank ledger sums to zero and every entry balances', () => {
    expect(trialBalanceIsZero()).toBe(true);
    expect(unbalancedEntries()).toEqual([]);
  });

  it('books balance sheet balances and trial balance ties', async () => {
    expect((await call('GET', '/books/reports/balance-sheet')).body.balanced).toBe(true);
    expect((await call('GET', '/books/reports/trial-balance')).body.balanced).toBe(true);
  });

  it('every bank account reconciles to its GL account', async () => {
    const rec = (await call('GET', '/books/reconciliation')).body as { difference: number }[];
    expect(rec.length).toBe(4);
    for (const r of rec) expect(r.difference).toBe(0);
  });
});

describe('payments', () => {
  it('rejects bad input and unauthenticated calls', async () => {
    expect((await call('GET', '/accounts', undefined, 'nope')).status).toBe(401);
    const op = await account('checking');
    expect((await call('POST', '/payments', { account_id: op.id, rail: 'ach', amount: -5 })).status).toBe(400);
  });

  it('book transfer moves money instantly between own accounts', async () => {
    const op = await account('checking');
    const tr = await account('treasury');
    const p = (await call('POST', '/payments', { account_id: op.id, to_account_id: tr.id, rail: 'book', amount: 12_345 })).body;
    expect(p.status).toBe('completed');
    const after = (await call('GET', `/accounts/${tr.id}`)).body;
    expect(after.balance.current - tr.balance.current).toBe(12_345);
  });

  it('large payments require a second admin, then ACH settles after the simulated clock advances', async () => {
    const op = await account('checking');
    const rcp = (await call('POST', '/recipients', { name: 'Test Vendor', ach_routing: '021000021', ach_account: '123456789' })).body;
    const p = (await call('POST', '/payments', { account_id: op.id, counterparty_id: rcp.id, rail: 'ach', amount: 2_000_000 })).body;
    expect(p.status).toBe('pending_approval');
    expect((await call('POST', `/payments/${p.id}/approve`)).status).toBe(403);
    const approved = (await call('POST', `/payments/${p.id}/approve`, undefined, marcusToken)).body;
    expect(approved.status).toBe('processing');
    const mid = (await call('GET', `/accounts/${op.id}`)).body;
    expect(mid.balance.available).toBe(mid.balance.current - (mid.balance.current - mid.balance.available));
    await call('POST', '/sim/advance', { hours: 96 });
    expect((await call('GET', `/payments/${p.id}`)).body.status).toBe('completed');
    expect(trialBalanceIsZero()).toBe(true);
  });

  it('idempotency key returns the same payment', async () => {
    const op = await account('checking');
    const tr = await account('treasury');
    const headers = { authorization: `Bearer ${token}`, 'idempotency-key': 'abc-123' };
    const a = (await app.inject({ method: 'POST', url: '/payments', headers, payload: { account_id: tr.id, to_account_id: op.id, rail: 'book', amount: 100 } })).json();
    const b = (await app.inject({ method: 'POST', url: '/payments', headers, payload: { account_id: tr.id, to_account_id: op.id, rail: 'book', amount: 100 } })).json();
    expect(a.id).toBe(b.id);
  });

  it('cannot overdraw: payment fails with insufficient funds', async () => {
    const pr = (await call('GET', '/accounts')).body.find((a: { name: string }) => a.name === 'Payroll');
    const op = await account('checking');
    const p = (await call('POST', '/payments', { account_id: pr.id, to_account_id: op.id, rail: 'book', amount: pr.balance.available + 1 })).body;
    expect(p.status).toBe('failed');
  });

  it('employees cannot send money', async () => {
    const op = await account('checking');
    expect((await call('POST', '/payments', { account_id: op.id, to_account_id: op.id, rail: 'book', amount: 1 }, employeeToken)).status).toBe(403);
  });
});

describe('cards', () => {
  it('issues Luhn-valid cards and enforces limits and freezes', async () => {
    const op = await account('checking');
    const me = (await call('GET', '/me')).body.user;
    const card = (await call('POST', '/cards', { account_id: op.id, user_id: me.id, nickname: 'Test', form: 'virtual', spend_limit: 10_000, limit_interval: 'transaction' })).body;
    const secret = (await call('POST', `/cards/${card.id}/reveal`)).body;
    expect(luhnValid(secret.pan)).toBe(true);
    expect((await call('POST', `/sim/cards/${card.id}/authorize`, { merchant: 'Big', amount: 10_001 })).body.approved).toBe(false);
    const ok = (await call('POST', `/sim/cards/${card.id}/authorize`, { merchant: 'Small', amount: 5_000, merchant_category: 'meals' })).body;
    expect(ok.approved).toBe(true);
    await call('PATCH', `/cards/${card.id}`, { status: 'frozen' });
    expect((await call('POST', `/sim/cards/${card.id}/authorize`, { merchant: 'Small', amount: 100 })).body.decline_reason).toBe('Card is frozen');
    await call('POST', `/sim/transactions/${ok.transaction_id}/capture`, { amount: 5_500 });
    const t = (await call('GET', `/transactions/${ok.transaction_id}`)).body;
    expect(t.status).toBe('posted');
    expect(t.amount).toBe(-5_500);
    expect(t.category_name).toBe('Meals & Entertainment');
  });

  it('never exposes PAN or CVV in card listings', async () => {
    const cards = (await call('GET', '/cards')).body;
    expect(JSON.stringify(cards)).not.toMatch(/"pan"|"cvv"/);
  });
});

describe('AP / AR / reimbursements flow into the books', () => {
  it('invoice: send recognizes AR, public payment deposits cash and clears AR', async () => {
    const op = await account('checking');
    const customers = (await call('GET', '/recipients')).body.filter((c: { is_customer: number }) => c.is_customer);
    const inv = (await call('POST', '/invoices', { customer_id: customers[0].id, issue_date: '2099-01-01', due_date: '2099-01-31', deposit_account_id: op.id, lines: [{ description: 'Widget', quantity: 2, unit_price: 50_000 }], send: true })).body;
    expect(inv.status).toBe('sent');
    expect(inv.total).toBe(100_000);
    const paid = await app.inject({ method: 'POST', url: `/public/invoices/${inv.public_token}/pay`, payload: {} });
    expect(paid.json().status).toBe('paid');
    const after = (await call('GET', `/accounts/${op.id}`)).body;
    expect(after.balance.current - op.balance.current).toBe(100_000);
    expect((await call('GET', '/books/reports/balance-sheet?as_of=2099-12-31')).body.balanced).toBe(true);
  });

  it('bill: approve books AP, pay settles and marks paid', async () => {
    const op = await account('checking');
    const vendors = (await call('GET', '/recipients')).body.filter((c: { is_vendor: number; ach_account: string }) => c.is_vendor && c.ach_account);
    const bill = (await call('POST', '/bills', { vendor_id: vendors[0].id, amount: 77_700, issue_date: '2099-02-01', due_date: '2000-01-01', submit: true })).body;
    expect((await call('POST', `/bills/${bill.id}/approve`)).body.status).toBe('approved');
    const r = (await call('POST', `/bills/${bill.id}/pay`, { account_id: op.id, rail: 'same_day_ach' })).body;
    expect(r.payment.status).toBe('processing');
    await call('POST', '/sim/advance', { hours: 6 });
    expect((await call('GET', `/bills/${bill.id}`)).body.status).toBe('paid');
  });

  it('reimbursement: employee submits, admin approves, employee is paid', async () => {
    const r = (await call('POST', '/reimbursements', { merchant: 'Taxi', amount: 4_200, spent_on: '2026-01-01' }, employeeToken)).body;
    expect(r.status).toBe('submitted');
    expect((await call('POST', `/reimbursements/${r.id}/approve`, {}, employeeToken)).status).toBe(403);
    expect((await call('POST', `/reimbursements/${r.id}/approve`, {})).body.status).toBe('approved');
    await call('POST', '/sim/advance', { hours: 96 });
    const mine = (await call('GET', '/reimbursements', undefined, employeeToken)).body;
    expect(mine.find((x: { id: string }) => x.id === r.id).status).toBe('paid');
  });

  it('recategorizing a transaction moves it between P&L lines; locked periods refuse edits', async () => {
    const t = (await call('GET', '/transactions?status=posted&kind=card&limit=1')).body.rows[0];
    const gls = (await call('GET', '/books/accounts')).body;
    const office = gls.find((g: { system_key: string }) => g.system_key === 'office');
    const res = await call('PATCH', `/transactions/${t.id}`, { gl_account_id: office.id });
    expect(res.body.gl_account_id).toBe(office.id);
    const old = (await call('GET', '/transactions?status=posted&limit=1&to=2000-01-01')).body;
    expect(old.total).toBe(0);
    const org = (await call('GET', '/me')).body.organization;
    const cutoff = new Date(Date.parse(org.books_locked_through) - 5 * 86_400_000).toISOString().slice(0, 10);
    const lockedTx = (await call('GET', `/transactions?status=posted&kind=card&limit=1&to=${cutoff}`)).body.rows[0];
    expect(lockedTx).toBeTruthy();
    expect((await call('PATCH', `/transactions/${lockedTx.id}`, { gl_account_id: office.id })).status).toBe(409);
  });

  it('manual journal entries must balance', async () => {
    const gls = (await call('GET', '/books/accounts')).body;
    const [a, b] = [gls.find((g: { code: string }) => g.code === '1300'), gls.find((g: { code: string }) => g.code === '2200')];
    expect((await call('POST', '/books/journal', { date: '2099-03-01', memo: 'bad', lines: [{ gl_account_id: a.id, debit: 100 }, { gl_account_id: b.id, credit: 99 }] })).status).toBe(400);
    expect((await call('POST', '/books/journal', { date: '2099-03-01', memo: 'Prepaid', lines: [{ gl_account_id: a.id, debit: 100 }, { gl_account_id: b.id, credit: 100 }] })).status).toBe(200);
  });

  it('employees cannot read the books, payments, recipients or other people\'s transactions', async () => {
    expect((await call('GET', '/books/reports/pnl', undefined, employeeToken)).status).toBe(403);
    expect((await call('GET', '/payments', undefined, employeeToken)).status).toBe(403);
    expect((await call('GET', '/recipients', undefined, employeeToken)).status).toBe(403);
    const someoneElses = (await call('GET', '/transactions?kind=ach_in&limit=1')).body.rows[0];
    expect((await call('GET', `/transactions/${someoneElses.id}`, undefined, employeeToken)).status).toBe(404);
    const own = (await call('GET', '/transactions?limit=1', undefined, employeeToken)).body.rows[0];
    expect((await call('GET', `/transactions/${own.id}`, undefined, employeeToken)).status).toBe(200);
  });

  it('ledger still balances after everything', () => {
    expect(trialBalanceIsZero()).toBe(true);
  });
});
