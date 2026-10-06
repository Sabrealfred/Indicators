import { buildApp } from './app.js';
import { one } from './db.js';
import { tick } from './services/processor.js';
import { seed } from './seed.js';

if (!one('SELECT 1 FROM organizations LIMIT 1')) {
  console.log('Empty database — loading demo data…');
  seed();
}

const app = await buildApp();
const port = Number(process.env.PORT ?? 4000);
await app.listen({ port, host: '0.0.0.0' });
console.log(`LedgerBank API listening on http://localhost:${port}`);

// Back office runs every 10 seconds: settlements, captures, interest, month-end.
setInterval(() => {
  try {
    tick();
  } catch (e) {
    console.error('processor tick failed', e);
  }
}, 10_000);
