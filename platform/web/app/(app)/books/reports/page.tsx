'use client';

import Link from 'next/link';
import { Suspense, useState, type ReactNode } from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useApi } from '@/lib/api';
import { date } from '@/lib/format';
import { Button, Card, Empty, ErrorNote, Field, Input, Loading, PageHeader, Select, Table, Tabs, Td, Th, cx } from '@/components/ui';
import {
  AGING_BUCKETS, Amt, acct, csvAmt, downloadCsv, presetRange, useToday,
  type AgingReport, type BalanceSheet, type Pnl, type Preset, type ReportSection, type TrialBalance,
} from '../_shared';

type Tab = 'pnl' | 'bs' | 'tb' | 'ar' | 'ap';
const TABS: { value: Tab; label: string }[] = [
  { value: 'pnl', label: 'Profit & Loss' },
  { value: 'bs', label: 'Balance Sheet' },
  { value: 'tb', label: 'Trial Balance' },
  { value: 'ar', label: 'AR aging' },
  { value: 'ap', label: 'AP aging' },
];

// ---------- Accounting table primitives ----------

/** Accountant's double underline under grand totals. */
const DOUBLE = '[border-bottom:3px_double_currentColor]!';

const ledgerHref = (id: string) => (id === 'net_income' ? null : `/books/ledger?account=${id}`);

function SectionHead({ children }: { children: ReactNode }) {
  return (
    <tr>
      <td colSpan={2} className="px-4 pb-1 pt-5 text-[12px] font-semibold uppercase tracking-wide text-muted">{children}</td>
    </tr>
  );
}

function Line({ id, code, name, amount }: { id: string; code: string; name: string; amount: number }) {
  const href = ledgerHref(id);
  const label = <><span className="mr-2 text-muted tabular">{code}</span>{name}</>;
  return (
    <tr className="hover:bg-surface-2">
      <td className="py-1.5 pl-8 pr-4">{href ? <Link href={href} className="hover:text-brand-600 hover:underline">{label}</Link> : label}</td>
      <td className="px-4 py-1.5 text-right"><Amt cents={amount} /></td>
    </tr>
  );
}

function TotalRow({ label, cents, strong, double }: { label: string; cents: number; strong?: boolean; double?: boolean }) {
  return (
    <tr className={cx(strong && 'bg-surface-2')}>
      <td className={cx('px-4 py-2', strong ? 'font-semibold' : 'font-medium')}>{label}</td>
      <td className={cx('border-t border-line px-4 py-2 text-right', double && DOUBLE)}>
        <Amt cents={cents} bold />
      </td>
    </tr>
  );
}

function Section({ title, section, totalLabel }: { title: string; section: ReportSection; totalLabel: string }) {
  return (
    <>
      <SectionHead>{title}</SectionHead>
      {section.lines.length === 0 && (
        <tr><td colSpan={2} className="py-1.5 pl-8 text-muted">No activity</td></tr>
      )}
      {section.lines.map((l) => <Line key={l.id} {...l} />)}
      <TotalRow label={totalLabel} cents={section.total} />
    </>
  );
}

function ReportTable({ children }: { children: ReactNode }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[420px] border-collapse text-left">
        <tbody>{children}</tbody>
      </table>
    </div>
  );
}

function ReportCard({ title, subtitle, onCsv, children, controls }: { title: string; subtitle: ReactNode; onCsv?: () => void; children: ReactNode; controls?: ReactNode }) {
  return (
    <>
      {controls && <div className="mb-4 flex flex-wrap items-end gap-3 print:hidden">{controls}</div>}
      <Card padded={false}>
        <div className="flex flex-wrap items-start justify-between gap-3 border-b border-line px-5 py-4">
          <div>
            <h2 className="text-lg font-semibold">{title}</h2>
            <div className="text-[13px] text-muted">{subtitle}</div>
          </div>
          <div className="flex gap-2 print:hidden">
            {onCsv && <Button size="sm" onClick={onCsv}>Export CSV</Button>}
            <Button size="sm" onClick={() => window.print()}>Print</Button>
          </div>
        </div>
        <div className="pb-3">{children}</div>
      </Card>
    </>
  );
}

const sectionCsv = (title: string, s: ReportSection, totalLabel: string) => [
  [title, '', ''],
  ...s.lines.map((l) => ['', `${l.code} ${l.name}`, csvAmt(l.amount)]),
  [totalLabel, '', csvAmt(s.total)],
];

// ---------- Profit & Loss ----------

function ProfitLoss({ today }: { today: string }) {
  const [preset, setPreset] = useState<Preset>('ytd');
  const [custom, setCustom] = useState(() => presetRange('ytd', today));
  const range = preset === 'custom' ? custom : presetRange(preset, today);
  const valid = range.from && range.to && range.from <= range.to;
  const r = useApi<Pnl>(valid ? `/books/reports/pnl?from=${range.from}&to=${range.to}` : null);
  const d = r.data;

  return (
    <ReportCard
      title="Profit & Loss"
      subtitle={`${date(range.from)} – ${date(range.to)} · accrual basis`}
      onCsv={d && (() => downloadCsv(`profit-and-loss_${d.from}_${d.to}.csv`, [
        ['Profit & Loss', `${d.from} to ${d.to}`, ''],
        ['Section', 'Account', 'Amount'],
        ...sectionCsv('Revenue', d.revenue, 'Total revenue'),
        ...sectionCsv('Expenses', d.expenses, 'Total expenses'),
        ['Net income', '', csvAmt(d.net_income)],
      ]))}
      controls={
        <>
          <Field label="Period">
            <Select value={preset} onChange={(e) => {
              const p = e.target.value as Preset;
              if (p === 'custom') setCustom(range);
              setPreset(p);
            }} className="w-44">
              <option value="this_month">This month</option>
              <option value="last_month">Last month</option>
              <option value="qtd">Quarter to date</option>
              <option value="ytd">Year to date</option>
              <option value="custom">Custom range</option>
            </Select>
          </Field>
          {preset === 'custom' && (
            <>
              <Field label="From"><Input type="date" value={custom.from} onChange={(e) => setCustom({ ...custom, from: e.target.value })} className="w-40" /></Field>
              <Field label="To"><Input type="date" value={custom.to} onChange={(e) => setCustom({ ...custom, to: e.target.value })} className="w-40" /></Field>
            </>
          )}
        </>
      }
    >
      <ErrorNote error={valid ? r.error : 'Choose a valid date range.'} />
      {!d ? (valid && !r.error ? <Loading /> : null) : (
        <ReportTable>
          <Section title="Revenue" section={d.revenue} totalLabel="Total revenue" />
          <Section title="Expenses" section={d.expenses} totalLabel="Total expenses" />
          <tr><td colSpan={2} className="h-3" /></tr>
          <TotalRow label="Net income" cents={d.net_income} strong double />
        </ReportTable>
      )}
    </ReportCard>
  );
}

// ---------- Balance sheet ----------

function BalanceSheetReport({ today }: { today: string }) {
  const [asOf, setAsOf] = useState(today);
  const r = useApi<BalanceSheet>(asOf ? `/books/reports/balance-sheet?as_of=${asOf}` : null);
  const d = r.data;
  return (
    <ReportCard
      title="Balance Sheet"
      subtitle={<>As of {date(asOf)}{d && (d.balanced
        ? <span className="ml-2 font-medium text-pos">Balanced ✓</span>
        : <span className="ml-2 font-medium text-neg">Out of balance by {acct(d.assets.total - d.liabilities.total - d.equity.total)}</span>)}</>}
      onCsv={d && (() => downloadCsv(`balance-sheet_${d.as_of}.csv`, [
        ['Balance Sheet', `As of ${d.as_of}`, ''],
        ['Section', 'Account', 'Amount'],
        ...sectionCsv('Assets', d.assets, 'Total assets'),
        ...sectionCsv('Liabilities', d.liabilities, 'Total liabilities'),
        ...sectionCsv('Equity', d.equity, 'Total equity'),
        ['Total liabilities & equity', '', csvAmt(d.liabilities.total + d.equity.total)],
      ]))}
      controls={<Field label="As of"><Input type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} className="w-44" /></Field>}
    >
      <ErrorNote error={r.error} />
      {!d ? (!r.error && <Loading />) : (
        <ReportTable>
          <Section title="Assets" section={d.assets} totalLabel="Total assets" />
          <tr><td colSpan={2} className="h-2" /></tr>
          <TotalRow label="Total assets" cents={d.assets.total} strong double />
          <Section title="Liabilities" section={d.liabilities} totalLabel="Total liabilities" />
          <Section title="Equity" section={d.equity} totalLabel="Total equity" />
          <tr><td colSpan={2} className="h-2" /></tr>
          <TotalRow label="Total liabilities & equity" cents={d.liabilities.total + d.equity.total} strong double />
        </ReportTable>
      )}
    </ReportCard>
  );
}

// ---------- Trial balance ----------

function TrialBalanceReport({ today }: { today: string }) {
  const [asOf, setAsOf] = useState(today);
  const r = useApi<TrialBalance>(asOf ? `/books/reports/trial-balance?as_of=${asOf}` : null);
  const d = r.data;
  return (
    <ReportCard
      title="Trial Balance"
      subtitle={<>As of {date(asOf)}{d && (d.balanced ? <span className="ml-2 font-medium text-pos">Debits = credits ✓</span> : <span className="ml-2 font-medium text-neg">Out of balance</span>)}</>}
      onCsv={d && (() => downloadCsv(`trial-balance_${d.as_of}.csv`, [
        ['Code', 'Account', 'Type', 'Debit', 'Credit'],
        ...d.rows.map((x) => [x.code, x.name, x.type, x.debit ? csvAmt(x.debit) : '', x.credit ? csvAmt(x.credit) : '']),
        ['', 'Total', '', csvAmt(d.total_debit), csvAmt(d.total_credit)],
      ]))}
      controls={<Field label="As of"><Input type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} className="w-44" /></Field>}
    >
      <ErrorNote error={r.error} />
      {!d ? (!r.error && <Loading />) : !d.rows.length ? <Empty title="No postings yet" /> : (
        <Table>
          <thead>
            <tr><Th className="w-20">Code</Th><Th>Account</Th><Th className="hidden sm:table-cell">Type</Th><Th right>Debit</Th><Th right>Credit</Th></tr>
          </thead>
          <tbody>
            {d.rows.map((x) => (
              <tr key={x.id} className="hover:bg-surface-2">
                <Td className="py-2 text-muted tabular">{x.code}</Td>
                <Td className="py-2"><Link href={`/books/ledger?account=${x.id}`} className="hover:text-brand-600 hover:underline">{x.name}</Link></Td>
                <Td className="hidden py-2 capitalize text-muted sm:table-cell">{x.type}</Td>
                <Td right className="py-2">{x.debit ? acct(x.debit) : ''}</Td>
                <Td right className="py-2">{x.credit ? acct(x.credit) : ''}</Td>
              </tr>
            ))}
            <tr className="bg-surface-2 font-semibold">
              <Td className="py-2.5" />
              <Td className="py-2.5">Total</Td>
              <Td className="hidden py-2.5 sm:table-cell" />
              <Td right className={cx('py-2.5', DOUBLE)}>{acct(d.total_debit)}</Td>
              <Td right className={cx('py-2.5', DOUBLE)}>{acct(d.total_credit)}</Td>
            </tr>
          </tbody>
        </Table>
      )}
    </ReportCard>
  );
}

// ---------- Aging ----------

function Aging({ kind }: { kind: 'ar' | 'ap' }) {
  const r = useApi<AgingReport>(`/books/reports/${kind}-aging`);
  const d = r.data;
  const title = kind === 'ar' ? 'Accounts Receivable Aging' : 'Accounts Payable Aging';
  const who = kind === 'ar' ? 'Customer' : 'Vendor';
  const href = (id: string) => (kind === 'ar' ? `/invoices?id=${id}` : `/bills?id=${id}`);
  return (
    <ReportCard
      title={title}
      subtitle={d ? `As of ${date(d.as_of)} · days past due` : 'Days past due'}
      onCsv={d && (() => downloadCsv(`${kind}-aging_${d.as_of}.csv`, [
        [who, 'Reference', 'Due date', ...AGING_BUCKETS.map((b) => b.label), 'Total'],
        ...d.items.map((i) => [i.name, i.ref, i.due_date, ...AGING_BUCKETS.map((b) => (b.key === i.bucket ? csvAmt(i.open) : '')), csvAmt(i.open)]),
        ['Total', '', '', ...AGING_BUCKETS.map((b) => csvAmt(d.totals[b.key] ?? 0)), csvAmt(d.total)],
      ]))}
    >
      <ErrorNote error={r.error} />
      {!d ? (!r.error && <Loading />) : !d.items.length ? <Empty title={kind === 'ar' ? 'No open invoices' : 'No open bills'} hint="Nothing outstanding right now." /> : (
        <Table>
          <thead>
            <tr>
              <Th>{who}</Th><Th>Ref</Th><Th>Due</Th>
              {AGING_BUCKETS.map((b) => <Th key={b.key} right>{b.label}</Th>)}
              <Th right>Total</Th>
            </tr>
          </thead>
          <tbody>
            {d.items.map((i) => (
              <tr key={i.id} className="hover:bg-surface-2">
                <Td className="py-2 font-medium">{i.name}</Td>
                <Td className="py-2"><Link href={href(i.id)} className="text-muted hover:text-brand-600 hover:underline">{i.ref}</Link></Td>
                <Td className="whitespace-nowrap py-2 text-muted">{date(i.due_date)}</Td>
                {AGING_BUCKETS.map((b) => (
                  <Td key={b.key} right className={cx('py-2', b.key !== 'current' && b.key === i.bucket && 'text-neg')}>{b.key === i.bucket ? acct(i.open) : ''}</Td>
                ))}
                <Td right className="py-2 font-medium">{acct(i.open)}</Td>
              </tr>
            ))}
            <tr className="bg-surface-2 font-semibold">
              <Td className="py-2.5">Total</Td><Td className="py-2.5" /><Td className="py-2.5" />
              {AGING_BUCKETS.map((b) => <Td key={b.key} right className="py-2.5">{acct(d.totals[b.key] ?? 0)}</Td>)}
              <Td right className={cx('py-2.5', DOUBLE)}>{acct(d.total)}</Td>
            </tr>
          </tbody>
        </Table>
      )}
    </ReportCard>
  );
}

// ---------- Page ----------

function ReportsInner() {
  const params = useSearchParams();
  const router = useRouter();
  const path = usePathname();
  const initial = (params.get('tab') as Tab) || 'pnl';
  const [tab, setTab] = useState<Tab>(TABS.some((t) => t.value === initial) ? initial : 'pnl');
  const todayKey = useToday();

  const change = (t: Tab) => {
    setTab(t);
    router.replace(`${path}?tab=${t}`, { scroll: false });
  };

  return (
    <>
      <PageHeader title="Reports" subtitle="Financial statements generated live from your general ledger." />
      <div className="print:hidden"><Tabs value={tab} onChange={change} tabs={TABS} /></div>
      {tab === 'pnl' && <ProfitLoss key={todayKey} today={todayKey} />}
      {tab === 'bs' && <BalanceSheetReport key={todayKey} today={todayKey} />}
      {tab === 'tb' && <TrialBalanceReport key={todayKey} today={todayKey} />}
      {tab === 'ar' && <Aging kind="ar" />}
      {tab === 'ap' && <Aging kind="ap" />}
    </>
  );
}

export default function ReportsPage() {
  return (
    <Suspense fallback={<Loading />}>
      <ReportsInner />
    </Suspense>
  );
}
