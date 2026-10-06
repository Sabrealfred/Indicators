'use client';

import Link from 'next/link';
import { useApi } from '@/lib/api';
import { useSession } from '@/lib/session';
import { money, moneyCompact, shortDate } from '@/lib/format';
import type { Insights, Transaction } from '@/lib/types';
import { Card, Empty, Loading, Money, PageHeader, Stat, StatusBadge, Table, Td, Tr } from '@/components/ui';

function CashflowChart({ data }: { data: Insights['monthly_cashflow'] }) {
  if (!data.length) return <Empty title="No activity yet" />;
  const max = Math.max(...data.flatMap((d) => [d.money_in, d.money_out]), 1);
  const H = 160;
  return (
    <div>
      <div className="flex h-[180px] items-end gap-3 sm:gap-6">
        {data.map((d) => (
          <div key={d.month} className="flex flex-1 flex-col items-center gap-1">
            <div className="flex items-end gap-1" style={{ height: H }}>
              <div title={`In ${money(d.money_in)}`} className="w-3 rounded-t bg-emerald-500/80 sm:w-5" style={{ height: Math.max(2, (d.money_in / max) * H) }} />
              <div title={`Out ${money(d.money_out)}`} className="w-3 rounded-t bg-brand-500/70 sm:w-5" style={{ height: Math.max(2, (d.money_out / max) * H) }} />
            </div>
            <div className="text-[12px] text-muted">{new Date(d.month + '-15').toLocaleDateString('en-US', { month: 'short' })}</div>
          </div>
        ))}
      </div>
      <div className="mt-3 flex gap-4 text-[12px] text-muted">
        <span className="flex items-center gap-1.5"><span className="h-2 w-2 rounded-sm bg-emerald-500/80" />Money in</span>
        <span className="flex items-center gap-1.5"><span className="h-2 w-2 rounded-sm bg-brand-500/70" />Money out</span>
      </div>
    </div>
  );
}

export default function Home() {
  const { user } = useSession();
  const ins = useApi<Insights>(user.role === 'employee' ? null : '/insights');
  const tx = useApi<{ rows: Transaction[] }>('/transactions?limit=8');

  if (user.role === 'employee') {
    return (
      <>
        <PageHeader title={`Welcome, ${user.name.split(' ')[0]}`} subtitle="Your cards, recent spend and reimbursements." />
        <div className="mb-6 flex gap-2">
          <Link href="/cards" className="rounded-lg border border-line bg-surface px-3 py-2 font-medium hover:bg-surface-2">My cards</Link>
          <Link href="/reimbursements" className="rounded-lg border border-line bg-surface px-3 py-2 font-medium hover:bg-surface-2">Request reimbursement</Link>
        </div>
        <RecentTransactions rows={tx.data?.rows} />
      </>
    );
  }

  const d = ins.data;
  if (!d) return <Loading />;
  const todos = [
    d.pending_approvals > 0 && { href: '/payments?tab=approvals', text: `${d.pending_approvals} payment${d.pending_approvals > 1 ? 's' : ''} awaiting approval` },
    d.bills_due_7d.n > 0 && { href: '/bills', text: `${d.bills_due_7d.n} bill${d.bills_due_7d.n > 1 ? 's' : ''} due within 7 days (${money(d.bills_due_7d.s)})` },
    d.overdue_invoices.n > 0 && { href: '/invoices', text: `${d.overdue_invoices.n} overdue invoice${d.overdue_invoices.n > 1 ? 's' : ''} (${money(d.overdue_invoices.s)})` },
    d.reimbursements_to_review > 0 && { href: '/reimbursements', text: `${d.reimbursements_to_review} reimbursement${d.reimbursements_to_review > 1 ? 's' : ''} to review` },
  ].filter(Boolean) as { href: string; text: string }[];

  return (
    <>
      <PageHeader
        title={`Good ${new Date().getHours() < 12 ? 'morning' : 'afternoon'}, ${user.name.split(' ')[0]}`}
        actions={
          <>
            <Link href="/payments?new=1" className="inline-flex h-9 items-center rounded-lg bg-brand-600 px-3.5 font-medium text-white hover:bg-brand-700">Send money</Link>
            <Link href="/invoices?new=1" className="inline-flex h-9 items-center rounded-lg border border-line bg-surface px-3.5 font-medium hover:bg-surface-2">Request payment</Link>
          </>
        }
      />

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label="Total cash" value={money(d.total_cash)} hint={`Across ${d.accounts.filter((a) => a.type !== 'credit').length} accounts`} />
        <Stat label="Last 30 days in" value={money(d.last_30_days.money_in)} tone="pos" />
        <Stat label="Last 30 days out" value={money(d.last_30_days.money_out)} />
        <Stat label="Runway" value={d.runway_months ? `${d.runway_months} mo` : '∞'} hint={`Avg. net burn ${moneyCompact(d.avg_monthly_burn)}/mo`} />
      </div>

      {todos.length > 0 && (
        <div className="mt-4 flex flex-wrap gap-2">
          {todos.map((t) => (
            <Link key={t.href} href={t.href} className="rounded-full border border-amber-500/30 bg-amber-500/5 px-3 py-1 text-[13px] text-amber-800 hover:bg-amber-500/10 dark:text-amber-300">
              {t.text} →
            </Link>
          ))}
        </div>
      )}

      <div className="mt-6 grid gap-6 lg:grid-cols-3">
        <Card title="Cash flow" className="lg:col-span-2"><CashflowChart data={d.monthly_cashflow} /></Card>
        <Card title="Accounts" padded={false}>
          <ul className="divide-y divide-line">
            {d.accounts.map((a) => (
              <li key={a.id}>
                <Link href={`/accounts/${a.id}`} className="flex items-center justify-between px-5 py-3 hover:bg-surface-2">
                  <div>
                    <div className="font-medium">{a.name}</div>
                    <div className="text-[12px] text-muted">
                      {a.type === 'credit' ? `${money(a.balance.available)} available` : a.apy_bps ? `${(a.apy_bps / 100).toFixed(2)}% APY · ••${a.last4}` : `••${a.last4}`}
                    </div>
                  </div>
                  <Money cents={a.type === 'credit' ? -a.balance.current : a.balance.current} className="font-medium" />
                </Link>
              </li>
            ))}
          </ul>
        </Card>
      </div>

      <div className="mt-6 grid gap-6 lg:grid-cols-3">
        <div className="lg:col-span-2"><RecentTransactions rows={tx.data?.rows} /></div>
        <Card title="Top spend · 30 days">
          {d.top_spend_categories.length ? (
            <ul className="space-y-3">
              {d.top_spend_categories.map((c) => (
                <li key={c.name}>
                  <div className="flex justify-between text-[13px]"><span>{c.name}</span><Money cents={c.amount} /></div>
                  <div className="mt-1 h-1.5 rounded bg-surface-2"><div className="h-1.5 rounded bg-brand-500" style={{ width: `${(c.amount / d.top_spend_categories[0].amount) * 100}%` }} /></div>
                </li>
              ))}
            </ul>
          ) : <Empty title="No spend yet" />}
        </Card>
      </div>
    </>
  );
}

function RecentTransactions({ rows }: { rows?: Transaction[] }) {
  return (
    <Card title="Recent transactions" actions={<Link href="/transactions" className="text-[13px] text-brand-600">View all</Link>} padded={false}>
      {!rows ? <Loading /> : !rows.length ? <Empty title="No transactions yet" /> : (
        <Table>
          <tbody>
            {rows.map((t) => (
              <Tr key={t.id}>
                <Td className="w-20 text-muted">{shortDate(t.created_at)}</Td>
                <Td>
                  <div className="font-medium">{t.counterparty_name}</div>
                  <div className="text-[12px] text-muted">{t.account_name}{t.card_last4 ? ` · ••${t.card_last4}` : ''}</div>
                </Td>
                <Td>{t.status !== 'posted' && <StatusBadge status={t.status} />}</Td>
                <Td right><Money cents={t.amount} signed /></Td>
              </Tr>
            ))}
          </tbody>
        </Table>
      )}
    </Card>
  );
}
