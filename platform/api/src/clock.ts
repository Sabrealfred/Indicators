import { one, run } from './db.js';

// Simulated clock: real time plus an offset the simulator can push forward,
// so ACH settlement, interest and due dates can be exercised in seconds.
// During seeding a fixed time can be pinned.

let pinned: Date | null = null;

export function pin(d: Date | null) {
  pinned = d;
}

export function offsetMs(): number {
  const row = one<{ value: string }>("SELECT value FROM meta WHERE key = 'clock_offset_ms'");
  return row ? Number(row.value) : 0;
}

export function now(): Date {
  if (pinned) return new Date(pinned);
  return new Date(Date.now() + offsetMs());
}

export const nowIso = () => now().toISOString();
export const today = () => nowIso().slice(0, 10);

export function advance(ms: number) {
  run(
    "INSERT INTO meta(key, value) VALUES ('clock_offset_ms', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
    String(offsetMs() + ms),
  );
}

export function isBusinessDay(d: Date) {
  const day = d.getUTCDay();
  return day !== 0 && day !== 6;
}

export function addBusinessDays(d: Date, n: number): Date {
  const out = new Date(d);
  let left = n;
  while (left > 0) {
    out.setUTCDate(out.getUTCDate() + 1);
    if (isBusinessDay(out)) left--;
  }
  return out;
}

export const addHours = (d: Date, h: number) => new Date(d.getTime() + h * 3600_000);
export const addDays = (d: Date, n: number) => new Date(d.getTime() + n * 86_400_000);
