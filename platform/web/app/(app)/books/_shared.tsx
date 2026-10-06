'use client';

import Link from 'next/link';
import type { ReactNode } from 'react';
import { money, todayIso } from '@/lib/format';
import { useApi } from '@/lib/api';
import { Badge, Select, cx } from '@/components/ui';
import type { GlAccount } from '@/lib/types';

/** Accounting-style amount: negatives in parentheses, zero as a dash. */
export const acct = (cents: number) => (cents === 0 ? '—' : cents < 0 ? `(${money(-cents)})` : money(cents));

export function Amt({ cents, className, bold }: { cents: number; className?: string; bold?: boolean }) {
  return <span className={cx('tabular whitespace-nowrap', cents < 0 && 'text-neg', bold && 'font-semibold', className)}>{acct(cents)}</span>;
}

export const GL_TYPES = ['asset', 'liability', 'equity', 'revenue', 'expense'] as const;
export const GL_TYPE_LABEL: Record<string, string> = { asset: 'Assets', liability: 'Liabilities', equity: 'Equity', revenue: 'Revenue', expense: 'Expenses' };

const SOURCE_TONE: Record<string, 'gray' | 'blue' | 'green' | 'yellow' | 'red'> = {
  transaction: 'gray', transfer: 'blue', bill: 'yellow', invoice: 'green', manual: 'red',
};
export function SourceBadge({ type }: { type: string }) {
  return <Badge tone={SOURCE_TONE[type] ?? 'gray'}>{type.charAt(0).toUpperCase() + type.slice(1)}</Badge>;
}

/** Builds a CSV from rows and triggers a browser download. */
export function downloadCsv(filename: string, rows: (string | number | null | undefined)[][]) {
  const esc = (v: string | number | null | undefined) => {
    const s = v == null ? '' : String(v);
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  const blob = new Blob([rows.map((r) => r.map(esc).join(',')).join('\n')], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** Cents to a plain decimal string for CSV. */
export const csvAmt = (cents: number) => (cents / 100).toFixed(2);

export function LinkRow({ href, children }: { href: string; children: ReactNode }) {
  return <Link href={href} className="hover:text-brand-600 hover:underline">{children}</Link>;
}

export const iso = (d: Date) => d.toISOString().slice(0, 10);

export type Preset = 'this_month' | 'last_month' | 'qtd' | 'ytd' | 'custom';
export function presetRange(p: Exclude<Preset, 'custom'>, today: string): { from: string; to: string } {
  const [y, m] = today.split('-').map(Number);
  if (p === 'this_month') return { from: `${today.slice(0, 7)}-01`, to: today };
  if (p === 'last_month') {
    const start = new Date(Date.UTC(y, m - 2, 1));
    const end = new Date(Date.UTC(y, m - 1, 0));
    return { from: iso(start), to: iso(end) };
  }
  if (p === 'qtd') {
    const qm = Math.floor((m - 1) / 3) * 3;
    return { from: iso(new Date(Date.UTC(y, qm, 1))), to: today };
  }
  return { from: `${y}-01-01`, to: today };
}

export const AGING_BUCKETS: { key: string; label: string }[] = [
  { key: 'current', label: 'Current' },
  { key: '1_30', label: '1–30' },
  { key: '31_60', label: '31–60' },
  { key: '61_90', label: '61–90' },
  { key: 'over_90', label: '90+' },
];

export interface AgingReport {
  as_of: string;
  items: { id: string; name: string; ref: string; due_date: string; open: number; bucket: string }[];
  totals: Record<string, number>;
  total: number;
}

export interface ReportSection { lines: { id: string; code: string; name: string; amount: number }[]; total: number }
export interface Pnl { from: string; to: string; revenue: ReportSection; expenses: ReportSection; net_income: number }
export interface BalanceSheet { as_of: string; assets: ReportSection; liabilities: ReportSection; equity: ReportSection; balanced: boolean }
export interface TrialBalance { as_of: string; rows: { id: string; code: string; name: string; type: string; debit: number; credit: number }[]; total_debit: number; total_credit: number; balanced: boolean }
export interface PnlMonth { month: string; revenue: number; expenses: number; net_income: number }
export interface ReconRow { account_id: string; name: string; type: string; bank_balance: number; book_balance: number; difference: number; unreconciled: number }
export interface JournalLine { id: number; entry_id: string; gl_account_id: string; amount: number; description: string | null; code: string; name: string }
export interface JournalEntry { id: string; date: string; memo: string; source_type: string; source_id: string | null; created_by: string | null; created_at: string; lines: JournalLine[] }
export interface Rule { id: string; match_field: string; match_value: string; gl_account_id: string; priority: number; created_at: string; code: string; gl_name: string }

export const linkCls = 'text-[13px] text-brand-600 hover:underline';

/** "Today" on the simulated bank clock (falls back to the browser date while loading). */
export function useToday() {
  const clock = useApi<{ now: string }>('/sim/clock');
  return clock.data?.now.slice(0, 10) ?? todayIso();
}

export function GlSelect({ accounts, value, onChange, types, placeholder = 'Select account…' }: { accounts: GlAccount[]; value: string; onChange: (id: string) => void; types?: readonly string[]; placeholder?: string }) {
  return (
    <Select value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">{placeholder}</option>
      {GL_TYPES.filter((t) => !types || types.includes(t)).map((t) => {
        const group = accounts.filter((a) => a.type === t && !a.archived);
        if (!group.length) return null;
        return (
          <optgroup key={t} label={GL_TYPE_LABEL[t]}>
            {group.map((a) => <option key={a.id} value={a.id}>{a.code} · {a.name}</option>)}
          </optgroup>
        );
      })}
    </Select>
  );
}
