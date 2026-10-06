import Fastify from 'fastify';
import cors from '@fastify/cors';
import { ZodError } from 'zod';
import { AppError, one } from './db.js';
import { login, requireAuth } from './auth.js';
import { nowIso } from './clock.js';
import { trialBalanceIsZero } from './ledger.js';
import bankingRoutes from './routes/banking.js';
import operationsRoutes, { simulatorRoutes } from './routes/operations.js';
import booksRoutes from './routes/books.js';
import orgRoutes from './routes/org.js';
import { payInvoice, publicInvoice } from './services/payables.js';
import { z } from 'zod';

export async function buildApp() {
  const app = Fastify({ logger: process.env.NODE_ENV === 'test' ? false : { level: 'warn' } });
  await app.register(cors, { origin: true, exposedHeaders: ['content-disposition'] });

  app.setErrorHandler((err, _req, reply) => {
    if (err instanceof AppError) return reply.status(err.status).send({ error: err.code, message: err.message });
    if (err instanceof ZodError) {
      return reply.status(400).send({ error: 'invalid_request', message: err.issues.map((i) => `${i.path.join('.') || 'body'}: ${i.message}`).join('; ') });
    }
    const status = (err as { statusCode?: number }).statusCode;
    if (status && status < 500) return reply.status(status).send({ error: 'invalid_request', message: (err as Error).message });
    app.log.error(err);
    return reply.status(500).send({ error: 'internal', message: 'Internal error' });
  });

  // Public endpoints
  app.get('/health', async () => ({
    ok: true, now: nowIso(), ledger_balanced: trialBalanceIsZero(),
    seeded: !!one('SELECT 1 FROM organizations LIMIT 1'),
  }));
  app.post('/auth/login', async (req) => {
    const b = z.object({ email: z.string(), password: z.string() }).parse(req.body);
    return { token: login(b.email, b.password) };
  });
  app.get('/public/invoices/:token', async (req) => publicInvoice((req.params as { token: string }).token));
  app.post('/public/invoices/:token/pay', async (req) => {
    const b = z.object({ amount: z.number().int().positive().optional(), method: z.enum(['ach', 'card']).default('ach') }).parse(req.body ?? {});
    return payInvoice((req.params as { token: string }).token, b.amount, b.method);
  });

  // Authenticated API
  await app.register(async (priv) => {
    priv.addHook('preHandler', requireAuth);
    await priv.register(orgRoutes);
    await priv.register(bankingRoutes);
    await priv.register(operationsRoutes);
    await priv.register(booksRoutes);
    await priv.register(simulatorRoutes);
  });

  return app;
}
