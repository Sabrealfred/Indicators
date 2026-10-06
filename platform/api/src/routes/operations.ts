import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { assertRole } from '../services/banking.js';
import { authorize, capture, getCard, issueCard, listCards, publicCard, refund, revealCard, updateCard, voidAuthorization } from '../services/cards.js';
import {
  approveBill, createBill, createInvoice, getBill, getInvoice, listBills, listInvoices, listReimbursements, payBill,
  reviewReimbursement, sendInvoice, submitBill, submitReimbursement, updateBill, voidBill, voidInvoice,
} from '../services/payables.js';
import { forbidden } from '../db.js';
import { MERCHANT_CATEGORIES } from '../books.js';

const cents = z.number().int().positive();
const date = z.string().regex(/^\d{4}-\d{2}-\d{2}$/);
const id = (req: { params: unknown }) => (req.params as { id: string }).id;

export default async function operationsRoutes(app: FastifyInstance) {
  // ----- Cards -----
  app.get('/cards', async (req) => listCards(req.actor));
  app.get('/cards/:id', async (req) => {
    const c = getCard(req.actor.org_id, id(req));
    if (req.actor.role === 'employee' && c.user_id !== req.actor.id) throw forbidden();
    return publicCard(c);
  });
  app.post('/cards', async (req) => {
    const b = z.object({
      account_id: z.string(), user_id: z.string(), nickname: z.string().min(1).max(40), form: z.enum(['virtual', 'physical']),
      spend_limit: cents.nullable().optional(), limit_interval: z.enum(['transaction', 'daily', 'monthly', 'all_time']).nullable().optional(),
      blocked_categories: z.array(z.enum(MERCHANT_CATEGORIES)).optional(),
    }).parse(req.body);
    return issueCard(req.actor, b);
  });
  app.patch('/cards/:id', async (req) => {
    const b = z.object({
      nickname: z.string().min(1).max(40).optional(), status: z.enum(['active', 'frozen', 'canceled']).optional(),
      spend_limit: cents.nullable().optional(), limit_interval: z.enum(['transaction', 'daily', 'monthly', 'all_time']).nullable().optional(),
      blocked_categories: z.array(z.enum(MERCHANT_CATEGORIES)).optional(),
    }).parse(req.body);
    return updateCard(req.actor, id(req), b);
  });
  app.post('/cards/:id/reveal', async (req) => revealCard(req.actor, id(req)));
  app.get('/merchant-categories', async () => MERCHANT_CATEGORIES);

  // ----- Bills -----
  app.get('/bills', async (req) => { assertRole(req.actor, 'admin', 'bookkeeper'); return listBills(req.actor.org_id); });
  app.get('/bills/:id', async (req) => { assertRole(req.actor, 'admin', 'bookkeeper'); return getBill(req.actor.org_id, id(req)); });
  app.post('/bills', async (req) => {
    const b = z.object({
      vendor_id: z.string(), invoice_number: z.string().nullable().optional(), amount: cents, issue_date: date, due_date: date,
      gl_account_id: z.string().nullable().optional(), memo: z.string().nullable().optional(), attachment_name: z.string().nullable().optional(),
      submit: z.boolean().optional(),
    }).parse(req.body);
    return createBill(req.actor, b);
  });
  app.patch('/bills/:id', async (req) => {
    assertRole(req.actor, 'admin', 'bookkeeper');
    const b = z.object({
      invoice_number: z.string().nullable().optional(), amount: cents.optional(), issue_date: date.optional(), due_date: date.optional(),
      gl_account_id: z.string().nullable().optional(), memo: z.string().nullable().optional(),
    }).parse(req.body);
    return updateBill(req.actor, id(req), b);
  });
  app.post('/bills/:id/submit', async (req) => submitBill(req.actor, id(req)));
  app.post('/bills/:id/approve', async (req) => approveBill(req.actor, id(req)));
  app.post('/bills/:id/void', async (req) => voidBill(req.actor, id(req)));
  app.post('/bills/:id/pay', async (req) => {
    const b = z.object({ account_id: z.string(), rail: z.enum(['ach', 'same_day_ach', 'wire', 'intl_wire', 'check']), scheduled_for: date.nullable().optional() }).parse(req.body);
    return payBill(req.actor, id(req), b);
  });

  // ----- Invoices -----
  app.get('/invoices', async (req) => { assertRole(req.actor, 'admin', 'bookkeeper'); return listInvoices(req.actor.org_id); });
  app.get('/invoices/:id', async (req) => { assertRole(req.actor, 'admin', 'bookkeeper'); return getInvoice(req.actor.org_id, id(req)); });
  app.post('/invoices', async (req) => {
    const b = z.object({
      customer_id: z.string(), issue_date: date, due_date: date, deposit_account_id: z.string(), memo: z.string().nullable().optional(),
      lines: z.array(z.object({ description: z.string().min(1), quantity: z.number().positive(), unit_price: cents, gl_account_id: z.string().nullable().optional() })).min(1),
      send: z.boolean().optional(),
    }).parse(req.body);
    const inv = createInvoice(req.actor, b);
    return b.send ? sendInvoice(req.actor, inv.id) : inv;
  });
  app.post('/invoices/:id/send', async (req) => sendInvoice(req.actor, id(req)));
  app.post('/invoices/:id/void', async (req) => { assertRole(req.actor, 'admin', 'bookkeeper'); return voidInvoice(req.actor, id(req)); });

  // ----- Reimbursements -----
  app.get('/reimbursements', async (req) => listReimbursements(req.actor));
  app.post('/reimbursements', async (req) => {
    const b = z.object({
      merchant: z.string().min(1), amount: cents, spent_on: date, gl_account_id: z.string().nullable().optional(),
      description: z.string().nullable().optional(), receipt_name: z.string().nullable().optional(),
    }).parse(req.body);
    return submitReimbursement(req.actor, b);
  });
  app.post('/reimbursements/:id/approve', async (req) => {
    const b = z.object({ account_id: z.string().optional() }).parse(req.body ?? {});
    return reviewReimbursement(req.actor, id(req), 'approve', b.account_id);
  });
  app.post('/reimbursements/:id/reject', async (req) => reviewReimbursement(req.actor, id(req), 'reject'));
}

/** Card network & payment-rail simulator: the dummy "outside world". */
export async function simulatorRoutes(app: FastifyInstance) {
  const { advance, nowIso } = await import('../clock.js');
  const { tick } = await import('../services/processor.js');
  const { receiveFunds, returnPayment } = await import('../services/banking.js');

  app.get('/sim/clock', async () => ({ now: nowIso() }));
  app.post('/sim/advance', async (req) => {
    assertRole(req.actor, 'admin');
    const b = z.object({ hours: z.number().positive().max(24 * 120) }).parse(req.body);
    let left = b.hours;
    let result = tick();
    while (left > 0) {
      const step = Math.min(left, 6);
      advance(step * 3600_000);
      left -= step;
      result = tick();
    }
    return { now: nowIso(), last_tick: result };
  });
  app.post('/sim/tick', async (req) => { assertRole(req.actor, 'admin'); return tick(); });
  app.post('/sim/incoming', async (req) => {
    assertRole(req.actor, 'admin');
    const b = z.object({
      account_id: z.string(), amount: cents, rail: z.enum(['ach', 'wire', 'intl_wire', 'check']).default('ach'),
      counterparty_name: z.string().min(1), description: z.string().default('Incoming payment'),
    }).parse(req.body);
    const kind = b.rail === 'ach' ? 'ach_in' : b.rail === 'check' ? 'check_deposit' : `${b.rail}_in`;
    return { transaction_id: receiveFunds(req.actor.org_id, b.account_id, { ...b, kind }) };
  });
  app.post('/sim/cards/:id/authorize', async (req) => {
    assertRole(req.actor, 'admin');
    const b = z.object({ merchant: z.string().min(1), amount: cents, merchant_category: z.enum(MERCHANT_CATEGORIES).optional(), mcc: z.string().optional() }).parse(req.body);
    return authorize(req.actor.org_id, id(req), b);
  });
  app.post('/sim/transactions/:id/capture', async (req) => {
    assertRole(req.actor, 'admin');
    const b = z.object({ amount: cents.optional() }).parse(req.body ?? {});
    capture(req.actor.org_id, id(req), b.amount);
    return { ok: true };
  });
  app.post('/sim/transactions/:id/void', async (req) => { assertRole(req.actor, 'admin'); voidAuthorization(req.actor.org_id, id(req)); return { ok: true }; });
  app.post('/sim/transactions/:id/refund', async (req) => {
    assertRole(req.actor, 'admin');
    const b = z.object({ amount: cents.optional() }).parse(req.body ?? {});
    return { transaction_id: refund(req.actor.org_id, id(req), b.amount) };
  });
  app.post('/sim/payments/:id/return', async (req) => {
    const b = z.object({ reason: z.string().optional() }).parse(req.body ?? {});
    assertRole(req.actor, 'admin');
    return returnPayment(req.actor, id(req), b.reason);
  });
}
