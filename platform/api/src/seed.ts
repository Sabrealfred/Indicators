import { db, newId, one, run } from './db.js';
import { addBusinessDays, addDays, pin } from './clock.js';
import { ensureSystemAccounts } from './ledger.js';
import { glByKey, seedChartOfAccounts } from './books.js';
import { hashPassword } from './auth.js';
import { Actor, approvePayment, createAccount, createPayment, receiveFunds } from './services/banking.js';
import { authorize, issueCard } from './services/cards.js';
import { approveBill, createBill, createInvoice, payBill, payInvoice, reviewReimbursement, sendInvoice, submitReimbursement } from './services/payables.js';
import { tick } from './services/processor.js';

// Builds a realistic six-month history for a fictional seed-stage startup by driving
// the real services day by day on a pinned clock. Deterministic for a given start date.

function mulberry32(seed: number) {
  return () => {
    seed |= 0;
    seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const rand = mulberry32(20261006);
const between = (min: number, max: number) => Math.round(min + rand() * (max - min));
const dollars = (min: number, max: number) => between(min * 100, max * 100);
const iso = (d: Date) => d.toISOString().slice(0, 10);
const at = (day: Date, hour: number) => new Date(Date.UTC(day.getUTCFullYear(), day.getUTCMonth(), day.getUTCDate(), hour, between(0, 59)));

export const DEMO_PASSWORD = 'demo1234';

export function seed(days = 182) {
  const d = db();
  if (one('SELECT 1 FROM organizations LIMIT 1')) throw new Error('Database already seeded');
  const realToday = new Date();
  const start = addDays(new Date(Date.UTC(realToday.getUTCFullYear(), realToday.getUTCMonth(), realToday.getUTCDate())), -days);
  pin(at(start, 9));

  ensureSystemAccounts();
  const orgId = newId('org');
  run(
    'INSERT INTO organizations(id, name, legal_name, ein, address, approval_threshold, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
    orgId, 'Acme Robotics', 'Acme Robotics, Inc.', '87-1234567', '548 Market St, San Francisco, CA 94104', 1_000_000, start.toISOString(),
  );
  seedChartOfAccounts(orgId);

  const mkUser = (email: string, name: string, role: Actor['role']): Actor => {
    const id = newId('user');
    run('INSERT INTO users(id, org_id, email, name, role, password_hash, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)', id, orgId, email, name, role, hashPassword(DEMO_PASSWORD), start.toISOString());
    return { id, org_id: orgId, role, name };
  };
  const ana = mkUser('demo@ledgerbank.dev', 'Ana Rivera', 'admin');
  const marcus = mkUser('marcus@acmerobotics.dev', 'Marcus Lee', 'admin');
  mkUser('priya@acmerobotics.dev', 'Priya Shah', 'bookkeeper');
  const diego = mkUser('diego@acmerobotics.dev', 'Diego Santos', 'employee');
  const emily = mkUser('emily@acmerobotics.dev', 'Emily Chen', 'employee');

  const operating = createAccount(orgId, { name: 'Operating', type: 'checking' });
  const payroll = createAccount(orgId, { name: 'Payroll', type: 'checking' });
  const treasury = createAccount(orgId, { name: 'Treasury', type: 'treasury', apy_bps: 410 });
  const io = createAccount(orgId, { name: 'IO Credit Card', type: 'credit', credit_limit: 15_000_000 });

  const gl = (key: string) => glByKey(orgId, key).id;
  const cp = (name: string, opts: Record<string, string | number | null> = {}) => {
    const id = newId('cp');
    run(
      `INSERT INTO counterparties(id, org_id, name, email, type, ach_routing, ach_account, wire_routing, wire_account, swift, iban, country, address, default_gl_account_id, is_customer, is_vendor, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      id, orgId, name, opts.email ?? null, opts.type ?? 'business', opts.ach_routing ?? '021000021', opts.ach_account ?? String(between(10_000_000, 99_999_999)),
      opts.wire_routing ?? '026009593', opts.wire_account ?? String(between(10_000_000, 99_999_999)), opts.swift ?? null, opts.iban ?? null,
      opts.country ?? 'US', opts.address ?? '100 Main St, New York, NY 10001', opts.gl ?? null, opts.customer ? 1 : 0, opts.vendor ? 1 : 0, start.toISOString(),
    );
    return id;
  };

  const vendors = {
    gusto: cp('Gusto Payroll', { gl: gl('payroll'), vendor: 1 }),
    rent: cp('Foundry Workspace LLC', { gl: gl('rent'), vendor: 1, email: 'ar@foundryworkspace.example' }),
    legal: cp('Hale & Brandt LLP', { gl: gl('professional_services'), vendor: 1, email: 'billing@halebrandt.example' }),
    insurance: cp('Embark Insurance', { gl: gl('insurance'), vendor: 1 }),
    studio: cp('Studio Kinetik GmbH', { gl: gl('contractors'), vendor: 1, country: 'DE', swift: 'DEUTDEFF', iban: 'DE89370400440532013000', address: 'Torstraße 1, 10119 Berlin' }),
    cpa: cp('Ledgerline CPAs', { gl: gl('professional_services'), vendor: 1 }),
  };
  const customers = [
    { id: cp('Brightline Logistics', { customer: 1, email: 'ap@brightline.example' }), base: 24_000, terms: 30 },
    { id: cp('Nova Health Systems', { customer: 1, email: 'payables@novahealth.example' }), base: 41_000, terms: 30 },
    { id: cp('Pinecrest Retail Group', { customer: 1, email: 'finance@pinecrest.example' }), base: 15_500, terms: 15 },
    { id: cp('Atlas Freight Co.', { customer: 1, email: 'ap@atlasfreight.example' }), base: 9_800, terms: 15 },
    { id: cp('Helio Energy', { customer: 1, email: 'accounts@helio.example' }), base: 32_000, terms: 45 },
  ];
  const investor = 'Northbeam Ventures Fund II';

  // Cards
  const cards = {
    cloud: issueCard(ana, { account_id: io.id, user_id: ana.id, nickname: 'Cloud & Infra', form: 'virtual' }).id,
    software: issueCard(ana, { account_id: io.id, user_id: ana.id, nickname: 'SaaS Subscriptions', form: 'virtual', spend_limit: 1_500_000, limit_interval: 'monthly' }).id,
    ana: issueCard(ana, { account_id: io.id, user_id: ana.id, nickname: 'Ana — Travel', form: 'physical' }).id,
    marketing: issueCard(ana, { account_id: io.id, user_id: emily.id, nickname: 'Growth Marketing', form: 'physical', spend_limit: 3_500_000, limit_interval: 'monthly' }).id,
    diego: issueCard(ana, { account_id: operating.id, user_id: diego.id, nickname: 'Diego — Field Ops', form: 'physical', spend_limit: 400_000, limit_interval: 'monthly', blocked_categories: ['advertising'] }).id,
  };

  // [merchant, category, min$, max$, card, daily probability]
  const daily: [string, string | null, number, number, keyof typeof cards, number][] = [
    ['Uber', 'travel', 12, 68, 'ana', 0.25],
    ['Lyft', 'travel', 10, 45, 'diego', 0.15],
    ['Sweetgreen', 'meals', 14, 42, 'ana', 0.2],
    ['Blue Bottle Coffee', 'meals', 6, 28, 'diego', 0.2],
    ['Amazon Business', 'office', 25, 480, 'diego', 0.12],
    ['Google Ads', 'advertising', 300, 2400, 'marketing', 0.3],
    ['LinkedIn Ads', 'advertising', 250, 1800, 'marketing', 0.15],
    ['Delta Air Lines', 'travel', 280, 1150, 'ana', 0.04],
    ['Marriott Hotels', 'travel', 220, 890, 'ana', 0.04],
    ['Home Depot', 'office', 40, 360, 'diego', 0.06],
    ['PAYPAL *DIGITALGOODS', null, 20, 240, 'marketing', 0.05],
    ['SQ *MERIDIAN PRINT', null, 45, 380, 'diego', 0.04],
  ];
  // [merchant, category, min$, max$, card, day-of-month]
  const monthly: [string, string, number, number, keyof typeof cards, number][] = [
    ['Amazon Web Services', 'cloud', 7_800, 13_500, 'cloud', 2],
    ['Google Cloud', 'cloud', 1_200, 2_600, 'cloud', 3],
    ['GitHub', 'software', 840, 840, 'software', 5],
    ['Slack Technologies', 'software', 612, 690, 'software', 6],
    ['Figma', 'software', 450, 450, 'software', 7],
    ['Google Workspace', 'software', 518, 560, 'software', 8],
    ['Notion Labs', 'software', 240, 240, 'software', 9],
    ['Linear', 'software', 192, 192, 'software', 9],
    ['Vercel', 'cloud', 420, 780, 'cloud', 11],
    ['Comcast Business', 'utilities', 389, 389, 'software', 14],
    ['Datadog', 'cloud', 980, 1_650, 'cloud', 16],
  ];

  const schedule = new Map<string, (() => void)[]>();
  const later = (date: Date, fn: () => void) => {
    const k = iso(date);
    schedule.set(k, [...(schedule.get(k) ?? []), fn]);
  };

  const payPayroll = (amount: number) => {
    const p = createPayment(ana, { account_id: payroll.id, counterparty_id: vendors.gusto, rail: 'ach', amount, memo: 'Payroll run', gl_account_id: gl('payroll') });
    if (p.status === 'pending_approval') approvePayment(marcus, p.id);
  };
  const topUpPayroll = (amount: number) => createPayment(ana, { account_id: operating.id, to_account_id: payroll.id, rail: 'book', amount, memo: 'Fund payroll' });

  let invoiceGrowth = 1;
  const lastDay = days - 1;

  for (let i = 0; i < days; i++) {
    const day = addDays(start, i);
    const dom = day.getUTCDate();
    const dow = day.getUTCDay();
    const weekday = dow !== 0 && dow !== 6;
    const isLastBizDay = weekday && addBusinessDays(day, 1).getUTCMonth() !== day.getUTCMonth();
    const isMidPayday = weekday && (dom === 14 || (dow === 1 && (dom === 15 || dom === 16)));

    pin(at(day, 8));
    tick();
    pin(at(day, 14));

    if (i === 0) {
      receiveFunds(orgId, operating.id, { amount: 450_000_000, kind: 'wire_in', rail: 'wire', counterparty_name: investor, description: 'Seed preferred financing', gl_account_id: gl('capital') });
      createPayment(ana, { account_id: operating.id, to_account_id: treasury.id, rail: 'book', amount: 250_000_000, memo: 'Move reserves to Treasury' });
      topUpPayroll(25_000_000);
    }
    if (i === 95) {
      createPayment(ana, { account_id: treasury.id, to_account_id: operating.id, rail: 'book', amount: 60_000_000, memo: 'Rebalance from Treasury' });
    }

    for (const fn of schedule.get(iso(day)) ?? []) fn();
    schedule.delete(iso(day));

    // Monthly AP: rent on the 1st, legal on the 10th, CPA on the 12th, insurance quarterly.
    if (dom === 1 && i < lastDay - 3) {
      const b = createBill(ana, { vendor_id: vendors.rent, invoice_number: `FW-${iso(day).slice(0, 7)}`, amount: 1_850_000, issue_date: iso(day), due_date: iso(addDays(day, 4)), memo: 'Suite 400 monthly rent', submit: true });
      approveBill(marcus, b.id);
      payBill(ana, b.id, { account_id: operating.id, rail: 'ach' });
      if (day.getUTCMonth() % 3 === 0) {
        const ins = createBill(ana, { vendor_id: vendors.insurance, invoice_number: `EMB-Q${Math.floor(day.getUTCMonth() / 3) + 1}`, amount: 620_000, issue_date: iso(day), due_date: iso(addDays(day, 20)), memo: 'D&O + general liability', submit: true });
        approveBill(ana, ins.id);
        payBill(ana, ins.id, { account_id: operating.id, rail: 'ach' });
      }
    }
    if (dom === 10 && i < lastDay - 20) {
      const amt = dollars(3_800, 12_500);
      const b = createBill(ana, { vendor_id: vendors.legal, invoice_number: `HB-${between(20000, 29999)}`, amount: amt, issue_date: iso(day), due_date: iso(addDays(day, 30)), memo: 'Corporate & commercial counsel', submit: true });
      approveBill(marcus, b.id);
      payBill(ana, b.id, { account_id: operating.id, rail: 'wire', scheduled_for: iso(addDays(day, 28)) });
    }
    if (dom === 12) {
      const b = createBill(ana, { vendor_id: vendors.cpa, invoice_number: `LL-${iso(day).slice(0, 7)}`, amount: 225_000, issue_date: iso(day), due_date: iso(addDays(day, 15)), memo: 'Monthly bookkeeping & tax', submit: true });
      if (i < lastDay - 15) {
        approveBill(ana, b.id);
        payBill(ana, b.id, { account_id: operating.id, rail: 'ach' });
      }
    }

    // Contractor in Berlin, paid by international wire on the 20th.
    if (dom === 20) {
      const p = createPayment(ana, { account_id: operating.id, counterparty_id: vendors.studio, rail: 'intl_wire', amount: dollars(7_200, 9_400), memo: 'Industrial design retainer' });
      if (p.status === 'pending_approval') approvePayment(marcus, p.id);
    }

    // Payroll: 15th and month end, funded the business day before from Operating.
    const payrollAmount = dollars(96_000, 104_000) + Math.round(i * 12_000);
    if (isMidPayday || isLastBizDay) {
      topUpPayroll(payrollAmount + 500_000);
      payPayroll(payrollAmount);
    }

    // Monthly invoicing to customers; they pay within their terms (some late).
    if (dom === 1) {
      invoiceGrowth *= 1.07;
      for (const c of customers) {
        const qty = between(1, 3);
        const inv = createInvoice(ana, {
          customer_id: c.id, issue_date: iso(day), due_date: iso(addDays(day, c.terms)), deposit_account_id: operating.id,
          memo: 'Thank you for your business.',
          lines: [
            { description: 'Fleet autonomy platform — monthly subscription', quantity: 1, unit_price: Math.round(c.base * invoiceGrowth) * 100 },
            { description: 'Robot-hours (overage)', quantity: qty * 10, unit_price: 4_500, gl_account_id: gl('services_revenue') },
          ],
        });
        sendInvoice(ana, inv.id);
        const delay = c.terms + between(-8, 12);
        const token = one<{ public_token: string }>('SELECT public_token FROM invoices WHERE id = ?', inv.id)!.public_token;
        later(addDays(day, delay), () => payInvoice(token));
      }
    }

    // Card activity.
    for (const [merchant, category, min, max, card, p] of daily) {
      if (rand() < p * (weekday ? 1.2 : 0.4)) authorize(orgId, cards[card], { merchant, amount: dollars(min, max), merchant_category: category ?? undefined });
    }
    for (const [merchant, category, min, max, card, onDay] of monthly) {
      if (dom === onDay) authorize(orgId, cards[card], { merchant, amount: dollars(min, max), merchant_category: category });
    }

    // Occasional out-of-pocket expenses.
    if (rand() < 0.06) {
      const who = rand() < 0.5 ? diego : emily;
      const r = submitReimbursement(who, { merchant: rand() < 0.5 ? 'Caltrain' : 'Office Depot', amount: dollars(18, 160), spent_on: iso(addDays(day, -2)), gl_account_id: gl(rand() < 0.5 ? 'travel' : 'office'), description: 'Out of pocket' }) as { id: string };
      if (i < lastDay - 5) reviewReimbursement(ana, r.id, 'approve', operating.id);
    }

    pin(at(day, 23));
    tick();
  }

  // Leave work in progress so every queue in the product has something in it.
  const todayD = addDays(start, days - 1);
  pin(at(todayD, 15));
  createPayment(ana, { account_id: operating.id, counterparty_id: vendors.legal, rail: 'wire', amount: 2_450_000, memo: 'Series A diligence retainer' });
  createBill(ana, { vendor_id: vendors.legal, invoice_number: 'HB-31877', amount: 1_120_000, issue_date: iso(addDays(todayD, -6)), due_date: iso(addDays(todayD, 24)), memo: 'Trademark filings', submit: true });
  createBill(ana, { vendor_id: vendors.insurance, invoice_number: 'EMB-CYBER', amount: 340_000, issue_date: iso(addDays(todayD, -2)), due_date: iso(addDays(todayD, 5)), memo: 'Cyber policy add-on', attachment_name: 'embark-cyber.pdf' });
  submitReimbursement(emily, { merchant: 'SFO Parking', amount: 6_400, spent_on: iso(addDays(todayD, -1)), gl_account_id: gl('travel'), description: 'Customer visit — Nova Health', receipt_name: 'sfo-parking.jpg' });
  authorize(orgId, cards.ana, { merchant: 'United Airlines', amount: 64_820, merchant_category: 'travel' });
  authorize(orgId, cards.marketing, { merchant: 'Meta Ads', amount: 125_000, merchant_category: 'advertising' });
  authorize(orgId, cards.diego, { merchant: 'Google Ads', amount: 9_900, merchant_category: 'advertising' });

  run(
    'INSERT INTO categorization_rules(id, org_id, match_field, match_value, gl_account_id, priority, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
    newId('rule'), orgId, 'counterparty', 'Comcast', gl('utilities'), 10, todayD.toISOString(),
  );
  // Close the books two months back so the period lock is visible.
  const close = new Date(Date.UTC(todayD.getUTCFullYear(), todayD.getUTCMonth() - 1, 0));
  run('UPDATE organizations SET books_locked_through = ? WHERE id = ?', iso(close), orgId);

  pin(null);
  d.exec('PRAGMA optimize');
  return { orgId, login: { email: 'demo@ledgerbank.dev', password: DEMO_PASSWORD } };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const r = seed();
  console.log(`Seeded organization ${r.orgId}. Log in as ${r.login.email} / ${r.login.password}`);
}
