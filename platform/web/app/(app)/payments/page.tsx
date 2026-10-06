'use client';

import Link from 'next/link';
import { Suspense, useEffect, useMemo, useState, type ReactNode } from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { api, post, useApi } from '@/lib/api';
import { can, useSession } from '@/lib/session';
import { date, dateTime, money, RAIL_LABELS, shortDate, titleCase } from '@/lib/format';
import type { Payment } from '@/lib/types';
import { Button, Card, cx, Drawer, Empty, ErrorNote, Input, KeyValue, Loading, Money, PageHeader, Select, StatusBadge, Table, Tabs, Td, Th, Tr, useAction } from '@/components/ui';
import { RAIL_ETA, SendMoneyModal } from './_send-money';

type Tab = 'all' | 'approvals' | 'scheduled';

export default function PaymentsPage() {
  return (
    <Suspense fallback={null}>
      <Payments />
    </Suspense>
  );
}

function Payments() {
  const { user } = useSession();
  const sp = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const allowed = can.moveMoney(user.role);
  const initialTab = sp.get('tab');
  const [tab, setTab] = useState<Tab>(initialTab === 'approvals' || initialTab === 'scheduled' ? initialTab : 'all');
  const [sending, setSending] = useState(sp.get('new') === '1');
  const [openId, setOpenId] = useState<string | null>(sp.get('payment'));
  const [q, setQ] = useState('');
  const [status, setStatus] = useState('');
  const payments = useApi<Payment[]>(allowed ? '/payments' : null);

  // Drop one-shot query params (?new=1, ?payment=) once consumed, keep ?tab in sync.
  useEffect(() => {
    if (!allowed) return;
    const next = tab === 'all' ? pathname : `${pathname}?tab=${tab}`;
    router.replace(next, { scroll: false });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab]);

  const all = payments.data ?? [];
  const approvals = all.filter((p) => p.status === 'pending_approval');
  const scheduled = all.filter((p) => p.status === 'scheduled' || p.status === 'processing');

  const rows = useMemo(() => {
    const base = tab === 'approvals' ? approvals : tab === 'scheduled' ? scheduled : all;
    const s = q.trim().toLowerCase();
    return base.filter(
      (p) =>
        (!status || tab !== 'all' || p.status === status) &&
        (!s || (p.counterparty_name ?? p.to_account_name ?? '').toLowerCase().includes(s) || (p.memo ?? '').toLowerCase().includes(s)),
    );
  }, [tab, all, approvals, scheduled, q, status]);

  if (!allowed) {
    return (
      <>
        <PageHeader title="Payments" />
        <Card>
          <Empty title="Payments are managed by admins and bookkeepers" hint="Need to get paid back for a purchase? Submit a reimbursement request instead." action={<Link href="/reimbursements" className="text-brand-600">Go to reimbursements →</Link>} />
        </Card>
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Payments"
        subtitle="ACH, wires, checks and transfers between your accounts."
        actions={<Button variant="primary" onClick={() => setSending(true)}>Send money</Button>}
      />

      <Tabs
        value={tab}
        onChange={setTab}
        tabs={[
          { value: 'all', label: 'All payments' },
          { value: 'approvals', label: <>Approvals{approvals.length > 0 && <span className="ml-1.5 rounded-full bg-amber-500/15 px-1.5 text-[12px] text-amber-700 dark:text-amber-400">{approvals.length}</span>}</> },
          { value: 'scheduled', label: <>Scheduled{scheduled.length > 0 && <span className="ml-1.5 rounded-full bg-surface-2 px-1.5 text-[12px] text-muted">{scheduled.length}</span>}</> },
        ]}
      />

      <div className="mb-4 flex flex-wrap gap-3">
        <Input type="search" placeholder="Search recipient or memo" value={q} onChange={(e) => setQ(e.target.value)} className="max-w-xs" />
        {tab === 'all' && (
          <Select value={status} onChange={(e) => setStatus(e.target.value)} className="w-auto" aria-label="Status">
            <option value="">Any status</option>
            {['pending_approval', 'scheduled', 'processing', 'completed', 'failed', 'canceled', 'returned'].map((s) => (
              <option key={s} value={s}>{titleCase(s)}</option>
            ))}
          </Select>
        )}
      </div>

      <ErrorNote error={payments.error} />
      <Card padded={false}>
        {!payments.data ? (payments.error ? null : <Loading />) : !rows.length ? (
          <Empty
            title={tab === 'approvals' ? 'Nothing waiting for approval' : tab === 'scheduled' ? 'No scheduled payments' : all.length ? 'No matching payments' : 'No payments yet'}
            hint={tab === 'approvals' ? 'Payments that need an admin’s sign-off will show up here.' : undefined}
            action={!all.length && <Button variant="primary" onClick={() => setSending(true)}>Send money</Button>}
          />
        ) : (
          <PaymentTable rows={rows} tab={tab} onOpen={(p) => setOpenId(p.id)} onChanged={payments.reload} />
        )}
      </Card>

      <SendMoneyModal open={sending} onClose={() => setSending(false)} onSent={payments.reload} />
      {openId && <PaymentDrawer id={openId} onClose={() => setOpenId(null)} onChanged={payments.reload} />}
    </>
  );
}

function PaymentTable({ rows, tab, onOpen, onChanged }: { rows: Payment[]; tab: Tab; onOpen: (p: Payment) => void; onChanged: () => void }) {
  const { user } = useSession();
  const showActions = tab !== 'all';
  return (
    <Table>
      <thead>
        <tr>
          <Th className="w-24">Created</Th>
          <Th>To</Th>
          <Th>Method</Th>
          <Th>{tab === 'scheduled' ? 'Sends / settles' : 'Status'}</Th>
          <Th right>Amount</Th>
          {showActions && <Th />}
        </tr>
      </thead>
      <tbody>
        {rows.map((p) => (
          <Tr key={p.id} onClick={() => onOpen(p)}>
            <Td className="whitespace-nowrap text-muted">{shortDate(p.created_at)}</Td>
            <Td className="min-w-48">
              <div className="font-medium">{p.counterparty_name ?? p.to_account_name ?? '—'}</div>
              <div className="max-w-64 truncate text-[12px] text-muted">
                from {p.account_name}{p.memo ? ` · ${p.memo}` : ''}{tab === 'approvals' && p.created_by_name ? ` · by ${p.created_by_name}` : ''}
              </div>
            </Td>
            <Td className="whitespace-nowrap text-[13px]">{RAIL_LABELS[p.rail] ?? p.rail}</Td>
            <Td className="whitespace-nowrap">
              {tab === 'scheduled' ? (
                <span className="text-[13px]">{p.status === 'processing' ? `Settles ${dateTime(p.settle_at)}` : p.scheduled_for ? `Sends ${date(p.scheduled_for)}` : 'Sending now'}</span>
              ) : (
                <StatusBadge status={p.status} />
              )}
            </Td>
            <Td right className="whitespace-nowrap font-medium"><Money cents={p.amount} /></Td>
            {showActions && (
              <Td right className="whitespace-nowrap">
                <RowActions p={p} canApprove={can.approve(user.role)} onChanged={onChanged} />
              </Td>
            )}
          </Tr>
        ))}
      </tbody>
    </Table>
  );
}

function RowActions({ p, canApprove, onChanged }: { p: Payment; canApprove: boolean; onChanged: () => void }) {
  const act = useAction();
  const run = async (what: 'approve' | 'cancel') => {
    if (what === 'cancel' && !confirm(`Cancel this ${money(p.amount)} payment?`)) return;
    const r = await act.run(() => post(`/payments/${p.id}/${what}`));
    if (r) onChanged();
  };
  const cancelable = p.status === 'pending_approval' || p.status === 'scheduled';
  return (
    <div className="flex flex-col items-end gap-1" onClick={(e) => e.stopPropagation()}>
      <div className="flex justify-end gap-2">
        {p.status === 'pending_approval' && canApprove && (
          <Button size="sm" variant="primary" disabled={act.busy} onClick={() => run('approve')}>Approve</Button>
        )}
        {cancelable && (canApprove || p.status === 'scheduled') && (
          <Button size="sm" variant="danger" disabled={act.busy} onClick={() => run('cancel')}>Cancel</Button>
        )}
      </div>
      {act.error && <div className="max-w-56 whitespace-normal text-right text-[12px] text-neg">{act.error}</div>}
    </div>
  );
}

type TimelineStep = { label: string; at?: string | null; detail?: ReactNode; state: 'done' | 'current' | 'upcoming' | 'bad' };

function timeline(p: Payment): TimelineStep[] {
  const steps: TimelineStep[] = [{ label: 'Created', at: dateTime(p.created_at), detail: p.created_by_name ? `by ${p.created_by_name}` : undefined, state: 'done' }];

  if (p.approved_by_name) steps.push({ label: 'Approved', detail: `by ${p.approved_by_name}`, state: 'done' });
  else if (p.status === 'pending_approval') steps.push({ label: 'Awaiting approval', detail: 'An admin must approve this payment', state: 'current' });

  if (p.scheduled_for) {
    const sent = ['processing', 'completed', 'returned'].includes(p.status) || (p.status === 'failed' && !!p.settle_at);
    steps.push({ label: sent ? 'Sent' : 'Scheduled', at: date(p.scheduled_for), state: sent ? 'done' : p.status === 'scheduled' ? 'current' : 'upcoming' });
  }

  if (p.status === 'canceled') {
    steps.push({ label: 'Canceled', state: 'bad' });
    return steps;
  }

  if (p.status === 'processing') {
    steps.push({ label: 'Processing', detail: p.settle_at ? `Expected to settle ${dateTime(p.settle_at)}` : RAIL_ETA[p.rail], state: 'current' });
  } else if (['completed', 'returned'].includes(p.status) || (p.status === 'failed' && p.settle_at)) {
    steps.push({ label: 'Processing', detail: p.settle_at ? `Settlement ${dateTime(p.settle_at)}` : undefined, state: 'done' });
  } else if (p.status !== 'failed') {
    steps.push({ label: 'Processing', detail: `${RAIL_ETA[p.rail]} once sent`, state: 'upcoming' });
  }

  if (p.status === 'completed') steps.push({ label: 'Completed', at: dateTime(p.completed_at), state: 'done' });
  else if (p.status === 'failed') steps.push({ label: 'Failed', detail: p.failure_reason ?? undefined, state: 'bad' });
  else if (p.status === 'returned') steps.push({ label: 'Returned', detail: p.failure_reason ?? undefined, at: p.completed_at ? dateTime(p.completed_at) : undefined, state: 'bad' });
  else steps.push({ label: 'Completed', state: 'upcoming' });
  return steps;
}

function PaymentDrawer({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const { user } = useSession();
  const pay = useApi<Payment>(`/payments/${id}`);
  const act = useAction();
  const p = pay.data;

  const run = async (what: 'approve' | 'cancel') => {
    if (what === 'cancel' && !confirm('Cancel this payment?')) return;
    const r = await act.run(() => api(`/payments/${id}/${what}`, { method: 'POST', body: {} }));
    if (r) {
      pay.reload();
      onChanged();
    }
  };

  return (
    <Drawer open onClose={onClose} title="Payment">
      {!p ? (pay.error ? <ErrorNote error={pay.error} /> : <Loading />) : (
        <>
          <div className="mb-5 text-center">
            <div className="text-[13px] text-muted">{p.counterparty_name ?? p.to_account_name}</div>
            <div className="mt-1 text-3xl font-semibold tabular tracking-tight">{money(p.amount)}</div>
            <div className="mt-2"><StatusBadge status={p.status} /></div>
          </div>

          {(p.status === 'pending_approval' || p.status === 'scheduled') && (
            <div className="mb-5 flex justify-center gap-2">
              {p.status === 'pending_approval' && can.approve(user.role) && (
                <Button variant="primary" disabled={act.busy} onClick={() => run('approve')}>Approve</Button>
              )}
              {(can.approve(user.role) || p.status === 'scheduled' || p.created_by === user.id) && (
                <Button variant="danger" disabled={act.busy} onClick={() => run('cancel')}>Cancel payment</Button>
              )}
            </div>
          )}
          {act.error && <div className="mb-4"><ErrorNote error={act.error} /></div>}

          <KeyValue
            rows={[
              ['From', <Link key="f" href={`/accounts/${p.account_id}`} className="text-brand-600 hover:underline">{p.account_name}</Link>],
              ['To', p.to_account_id ? <Link key="t" href={`/accounts/${p.to_account_id}`} className="text-brand-600 hover:underline">{p.to_account_name}</Link> : p.counterparty_name ?? '—'],
              ['Method', RAIL_LABELS[p.rail] ?? p.rail],
              ['Delivery', RAIL_ETA[p.rail] ?? '—'],
              ['Memo', p.memo || '—'],
              ['Scheduled for', p.scheduled_for ? date(p.scheduled_for) : 'Immediately'],
              ['Created by', p.created_by_name ?? '—'],
              ['Approved by', p.approved_by_name ?? '—'],
              ...(p.bill_id ? ([['Source', <Link key="b" href="/bills" className="text-brand-600 hover:underline">Bill payment</Link>]] as [string, ReactNode][]) : []),
              ...(p.reimbursement_id ? ([['Source', <Link key="r" href="/reimbursements" className="text-brand-600 hover:underline">Reimbursement</Link>]] as [string, ReactNode][]) : []),
              ['Payment ID', <span key="id" className="font-mono text-[12px]">{p.id}</span>],
            ]}
          />

          <h3 className="mb-3 mt-6 text-[12px] font-semibold uppercase tracking-wider text-muted">Timeline</h3>
          <ol className="relative ml-2 border-l border-line">
            {timeline(p).map((s, i) => (
              <li key={i} className="mb-4 ml-4 last:mb-0">
                <span
                  className={cx(
                    'absolute -left-[5px] mt-1.5 h-2.5 w-2.5 rounded-full border-2 border-surface',
                    s.state === 'done' && 'bg-emerald-500',
                    s.state === 'current' && 'bg-amber-500',
                    s.state === 'upcoming' && 'bg-line',
                    s.state === 'bad' && 'bg-red-500',
                  )}
                />
                <div className={cx('font-medium', s.state === 'upcoming' && 'text-muted', s.state === 'bad' && 'text-neg')}>{s.label}</div>
                {(s.at || s.detail) && (
                  <div className="text-[12px] text-muted">
                    {s.at}
                    {s.at && s.detail ? ' · ' : ''}
                    {s.detail}
                  </div>
                )}
              </li>
            ))}
          </ol>
        </>
      )}
    </Drawer>
  );
}
