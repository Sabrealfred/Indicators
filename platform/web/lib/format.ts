const usd = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });
const usdCompact = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', notation: 'compact', maximumFractionDigits: 1 });

/** Formats integer cents as USD. */
export const money = (cents: number | null | undefined) => usd.format((cents ?? 0) / 100);
export const moneyCompact = (cents: number) => usdCompact.format(cents / 100);
/** Signed with an explicit + for inflows, as in a transaction feed. */
export const signedMoney = (cents: number) => (cents > 0 ? '+' : '') + money(cents);

/** Parses a user-typed dollar string ("1,250.50") into integer cents, or null if invalid. */
export function toCents(input: string): number | null {
  const clean = input.replace(/[$,\s]/g, '');
  if (!/^\d+(\.\d{0,2})?$/.test(clean)) return null;
  return Math.round(Number(clean) * 100);
}

export function date(iso: string | null | undefined) {
  if (!iso) return '—';
  const d = new Date(iso.length === 10 ? iso + 'T12:00:00Z' : iso);
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' });
}

export function shortDate(iso: string | null | undefined) {
  if (!iso) return '—';
  const d = new Date(iso.length === 10 ? iso + 'T12:00:00Z' : iso);
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' });
}

export function dateTime(iso: string | null | undefined) {
  if (!iso) return '—';
  return new Date(iso).toLocaleString('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
}

export const todayIso = () => new Date().toISOString().slice(0, 10);
export const addDaysIso = (iso: string, n: number) => new Date(Date.parse(iso + 'T12:00:00Z') + n * 86_400_000).toISOString().slice(0, 10);

export const RAIL_LABELS: Record<string, string> = {
  ach: 'ACH', same_day_ach: 'Same-day ACH', wire: 'Domestic wire', intl_wire: 'International wire', book: 'Internal transfer', check: 'Mailed check',
};

export const titleCase = (s: string) => s.replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
