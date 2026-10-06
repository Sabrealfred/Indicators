'use client';

import Link from 'next/link';
import { useState } from 'react';
import { post, useApi } from '@/lib/api';
import { useSession } from '@/lib/session';
import { date, money, moneyCompact } from '@/lib/format';
import type { GlAccount } from '@/lib/types';
import { Button, Card, Empty, ErrorNote, Loading, PageHeader, Stat, cx, useAction } from '@/components/ui';
import { Amt, linkCls, type AgingReport, type Pnl, type PnlMonth, type ReconRow } from './_shared';

function PnlChart({ data }: { data: PnlMonth[] }) {
  if (!data.length) return <Empty title="No activity yet" />;
  const max = Math.max(...data.flatMap((d) => [d.revenue, d.expenses, Math.abs(d.net_income)]), 1);
  const H = 160;
  return (
    <div>
      <div className="flex items-end gap-2 sm:gap-5">
        {data.map((d) => (
          <div key={d.month} className="flex min-w-0 flex-1 flex-col items-center gap-1">
            <div className="flex items-end gap-1" style={{ height: H }}>
              <div title={`Revenue ${money(d.revenue)}`} className="w-3 rounded-t bg-emerald-500/80 sm:w-5" style={{ height: Math.max(2, (d.revenue / max) * H) }} />
              <div title={`Expenses ${money(d.expenses)}`} className="w-3 rounded-t bg-brand-500/70 sm:w-5" style={{ height: Math.max(2, (d.expenses / max) * H) }} />
            </div>
            <div className="text-[12px] text-muted">{new Date(d.month + '-15').toLocaleDateString('en-US', { month: 'short' })}</div>
            <div className={cx('truncate text-[11px] font-medium tabular', d.net_income < 0 ? 'text-neg' : 'text-pos')} title={`Net income ${money(d.net_income)}`}>
              {d.net_income < 0 ? `(${moneyCompact(-d.net_income)})` : moneyCompact(d.net_income)}
            </div>
          </div>
        ))}
      </div>
      <div className="mt-3 flex flex-wrap gap-4 text-[12px] text-muted">
        <span className="flex items-center gap-1.5"><span className="h-2 w-2 rounded-sm bg-emerald-500/80" />Revenue</span>
        <span className="flex items-center gap-1.5"><span className="h-2 w-2 rounded-sm bg-brand-500/70" />Expenses</span>
        <span>Figures under each month are net income</span>
      </div>
    </div>
  );
}

export default function BooksOverview() {
  const { organization } = useSession();
  const pnl = useApi<Pnl>('/books/reports/pnl');
  const monthly = useApi<PnlMonth[]>('/books/reports/pnl-monthly?months=6');
  const accounts = useApi<GlAccount[]>('/books/accounts');
  const uncategorized = useApi<{ total: number }>('/transactions?uncategorized=true&limit=1');
  const recon = useApi<ReconRow[]>('/books/reconciliation');
  const ar = useApi<AgingReport>('/books/reports/ar-aging');
  const ap = useApi<AgingReport>('/books/reports/ap-aging');
  const auto = useAction();
  const [autoResult, setAutoResult] = useState<number | null>(null);

  const runAuto = async () => {
    const r = await auto.run(() => post<{ recategorized: number }>('/books/auto-categorize'));
    if (r) {
      setAutoResult(r.recategorized);
      [pnl, monthly, accounts, uncategorized].forEach((x) => x.reload());
    }
  };

  const err = pnl.error || monthly.error || accounts.error || recon.error || ar.error || ap.error;
  const cash = accounts.data?.filter((a) => a.subtype === 'bank').reduce((s, a) => s + a.balance, 0);
  const reconIssues = recon.data?.filter((r) => r.difference !== 0) ?? [];
  const unreconciled = recon.data?.reduce((s, r) => s + r.unreconciled, 0) ?? 0;

  return (
    <>
      <PageHeader
        title="Books"
        subtitle="Accrual general ledger kept in sync with your bank activity."
        actions={
          <>
            <Button onClick={runAuto} disabled={auto.busy}>{auto.busy ? 'Categorizing…' : 'Auto-categorize'}</Button>
            <Link href="/books/reports" className="inline-flex h-9 items-center rounded-lg bg-brand-600 px-3.5 font-medium text-white hover:bg-brand-700">View reports</Link>
          </>
        }
      />
      <div className="mb-4 space-y-2">
        <ErrorNote error={err || auto.error} />
        {autoResult !== null && (
          <div className="rounded-lg border border-emerald-500/30 bg-emerald-500/5 px-3 py-2 text-[13px] text-pos">
            Rules re-applied — {autoResult} transaction{autoResult === 1 ? '' : 's'} re-categorized.
          </div>
        )}
      </div>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label="YTD revenue" value={pnl.data ? money(pnl.data.revenue.total) : '…'} hint={pnl.data && `Since ${date(pnl.data.from)}`} />
        <Stat label="YTD expenses" value={pnl.data ? money(pnl.data.expenses.total) : '…'} />
        <Stat label="YTD net income" value={pnl.data ? <Amt cents={pnl.data.net_income} /> : '…'} tone={pnl.data && pnl.data.net_income >= 0 ? 'pos' : undefined} />
        <Stat label="Cash on books" value={cash !== undefined ? money(cash) : '…'} hint="Bank GL accounts" />
      </div>

      <div className="mt-6 grid grid-cols-1 gap-6 lg:grid-cols-3">
        <Card title="Monthly profit & loss" className="lg:col-span-2" actions={<Link href="/books/reports" className={linkCls}>Full P&amp;L</Link>}>
          {monthly.data ? <PnlChart data={monthly.data} /> : <Loading />}
        </Card>

        <Card title="Needs attention" padded={false}>
          <ul className="divide-y divide-line">
            <li className="flex items-center justify-between gap-3 px-5 py-3">
              <div>
                <div className="font-medium">Uncategorized transactions</div>
                <Link href="/transactions?uncategorized=true" className={linkCls}>Review →</Link>
              </div>
              <span className={cx('text-lg font-semibold tabular', (uncategorized.data?.total ?? 0) > 0 && 'text-amber-600')}>{uncategorized.data?.total ?? '…'}</span>
            </li>
            <li className="flex items-center justify-between gap-3 px-5 py-3">
              <div>
                <div className="font-medium">Reconciliation</div>
                <Link href="/books/reconcile" className={linkCls}>{unreconciled} unreconciled txns →</Link>
              </div>
              {!recon.data ? '…' : reconIssues.length ? (
                <span className="text-right text-[13px] text-neg">{reconIssues.length} account{reconIssues.length > 1 ? 's' : ''} off</span>
              ) : (
                <span className="text-[13px] font-medium text-pos">✓ Balanced</span>
              )}
            </li>
            <li className="flex items-center justify-between gap-3 px-5 py-3">
              <div>
                <div className="font-medium">Books locked through</div>
                <Link href="/books/reconcile" className={linkCls}>Manage close →</Link>
              </div>
              <span className="text-[13px] font-medium">{organization.books_locked_through ? date(organization.books_locked_through) : 'Open'}</span>
            </li>
            <li className="flex items-center justify-between gap-3 px-5 py-3">
              <div>
                <div className="font-medium">Receivables (AR)</div>
                <Link href="/books/reports?tab=ar" className={linkCls}>AR aging →</Link>
              </div>
              <div className="text-right">
                <div className="font-semibold tabular">{ar.data ? money(ar.data.total) : '…'}</div>
                {ar.data && ar.data.total - ar.data.totals.current > 0 && <div className="text-[12px] text-neg">{money(ar.data.total - ar.data.totals.current)} past due</div>}
              </div>
            </li>
            <li className="flex items-center justify-between gap-3 px-5 py-3">
              <div>
                <div className="font-medium">Payables (AP)</div>
                <Link href="/books/reports?tab=ap" className={linkCls}>AP aging →</Link>
              </div>
              <div className="text-right">
                <div className="font-semibold tabular">{ap.data ? money(ap.data.total) : '…'}</div>
                {ap.data && ap.data.total - ap.data.totals.current > 0 && <div className="text-[12px] text-neg">{money(ap.data.total - ap.data.totals.current)} past due</div>}
              </div>
            </li>
          </ul>
        </Card>
      </div>

      <div className="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
        {[
          ['/books/reports', 'Reports', 'P&L, balance sheet, trial balance, aging'],
          ['/books/ledger', 'Chart of accounts', 'Accounts and general ledger detail'],
          ['/books/journal', 'Journal entries', 'All postings and manual adjustments'],
          ['/books/reconcile', 'Reconciliation', 'Bank vs books and period close'],
          ['/books/rules', 'Rules', 'Auto-categorization rules'],
        ].map(([href, label, hint]) => (
          <Link key={href} href={href} className="rounded-xl border border-line bg-surface p-4 hover:bg-surface-2">
            <div className="font-medium">{label}</div>
            <div className="mt-0.5 text-[13px] text-muted">{hint}</div>
          </Link>
        ))}
      </div>
    </>
  );
}
