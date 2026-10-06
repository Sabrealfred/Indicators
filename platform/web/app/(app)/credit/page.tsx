'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import { post, useApi } from '@/lib/api';
import { can, useSession } from '@/lib/session';
import { money, shortDate } from '@/lib/format';
import type { Account, Payment, Transaction } from '@/lib/types';
import { Button, Card, Empty, ErrorNote, Field, Loading, Modal, Money, MoneyInput, PageHeader, Select, Stat, StatusBadge, Table, Td, Th, Tr, cx, useAction } from '@/components/ui';

export default function CreditPage() {
  const { user } = useSession();
  const accounts = useApi<Account[]>('/accounts');
  const credit = accounts.data?.find((a) => a.type === 'credit' && a.status !== 'closed');
  const tx = useApi<{ total: number; rows: Transaction[] }>(credit ? `/transactions?account_id=${credit.id}&limit=25` : null);
  const cashback = useApi<{ total: number; rows: Transaction[] }>(credit ? `/transactions?account_id=${credit.id}&kind=cashback&limit=500` : null);
  const [payOpen, setPayOpen] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  if (accounts.error) return <><PageHeader title="IO Credit" /><ErrorNote error={accounts.error} /></>;
  if (!accounts.data) return <Loading />;
  if (!credit) {
    return (
      <>
        <PageHeader title="IO Credit" />
        <Card><Empty title="No credit account" hint="Open a corporate credit account from Accounts to issue cards on credit." /></Card>
      </>
    );
  }

  const owed = Math.max(0, -credit.balance.current);
  const pending = -credit.balance.pending;
  const limit = credit.credit_limit;
  const available = credit.balance.available;
  const used = owed + Math.max(0, pending);
  const util = limit > 0 ? Math.min(100, (used / limit) * 100) : 0;
  const cashbackTotal = (cashback.data?.rows ?? []).filter((t) => t.status === 'posted').reduce((s, t) => s + t.amount, 0);
  const operating = accounts.data.find((a) => a.type === 'checking')?.name ?? 'Operating';
  const rows = (tx.data?.rows ?? []);

  return (
    <>
      <PageHeader
        title="IO Credit"
        subtitle="Corporate card with a revolving limit, paid in full every month."
        actions={can.moveMoney(user.role) && <Button variant="primary" disabled={owed <= 0} onClick={() => setPayOpen(true)}>Pay now</Button>}
      />

      {notice && <div className="mb-4 rounded-lg border border-emerald-500/30 bg-emerald-500/5 px-3 py-2 text-[13px] text-pos">{notice}</div>}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label="Balance owed" value={money(owed)} hint={pending > 0 ? `+ ${money(pending)} pending` : 'No pending charges'} />
        <Stat label="Available credit" value={money(available)} />
        <Stat label="Credit limit" value={money(limit)} />
        <Stat label="Cashback earned" value={money(cashbackTotal)} tone="pos" hint="1.5% on every purchase" />
      </div>

      <div className="mt-6 grid gap-6 lg:grid-cols-3">
        <Card title="Utilization" className="lg:col-span-2">
          <div className="flex items-baseline justify-between">
            <span className="text-2xl font-semibold tabular">{util.toFixed(1)}%</span>
            <span className="text-[13px] text-muted">{money(used)} of {money(limit)}</span>
          </div>
          <div className="mt-3 flex h-3 overflow-hidden rounded-full bg-surface-2">
            <div className={cx('h-3', util >= 90 ? 'bg-red-500' : util >= 70 ? 'bg-amber-500' : 'bg-brand-600')} style={{ width: `${limit ? (owed / limit) * 100 : 0}%` }} />
            <div className="h-3 bg-brand-500/40" style={{ width: `${limit ? (Math.max(0, pending) / limit) * 100 : 0}%` }} />
          </div>
          <div className="mt-3 flex flex-wrap gap-4 text-[12px] text-muted">
            <span className="flex items-center gap-1.5"><span className="h-2 w-2 rounded-sm bg-brand-600" />Posted {money(owed)}</span>
            <span className="flex items-center gap-1.5"><span className="h-2 w-2 rounded-sm bg-brand-500/40" />Pending {money(Math.max(0, pending))}</span>
            <span className="flex items-center gap-1.5"><span className="h-2 w-2 rounded-sm bg-surface-2 ring-1 ring-line" />Available {money(available)}</span>
          </div>
        </Card>
        <Card title="Repayment">
          <div className="space-y-3 text-[13px]">
            <div className="flex gap-3">
              <span className="mt-0.5 grid h-6 w-6 shrink-0 place-items-center rounded-full bg-brand-500/10 text-brand-600">↻</span>
              <div><div className="font-medium">Autopay in full on the 1st</div><div className="text-muted">Statement balance is paid automatically from {operating}.</div></div>
            </div>
            <div className="flex gap-3">
              <span className="mt-0.5 grid h-6 w-6 shrink-0 place-items-center rounded-full bg-emerald-500/10 text-pos">%</span>
              <div><div className="font-medium">1.5% cashback</div><div className="text-muted">Credited to this account each month on posted purchases.</div></div>
            </div>
            <div className="text-muted">No interest — the balance never revolves. Pay early any time to free up credit.</div>
          </div>
        </Card>
      </div>

      <Card title="Recent activity" className="mt-6" padded={false} actions={<Link href={`/transactions?account_id=${credit.id}`} className="text-[13px] text-brand-600">View all</Link>}>
        <ErrorNote error={tx.error} />
        {!tx.data ? <Loading /> : !rows.length ? <Empty title="No card activity yet" /> : (
          <Table>
            <thead>
              <tr><Th>Date</Th><Th>Description</Th><Th className="hidden sm:table-cell">Card</Th><Th className="hidden md:table-cell">Category</Th><Th right>Amount</Th></tr>
            </thead>
            <tbody>
              {rows.map((t) => (
                <Tr key={t.id}>
                  <Td className="whitespace-nowrap text-muted">{shortDate(t.created_at)}</Td>
                  <Td>
                    <div className="font-medium">{t.counterparty_name}</div>
                    <div className="flex items-center gap-1.5 text-[12px] text-muted">
                      {t.status !== 'posted' && <StatusBadge status={t.status} />}
                      <span className="sm:hidden">{t.card_last4 ? `••${t.card_last4}` : ''}</span>
                    </div>
                  </Td>
                  <Td className="hidden text-muted sm:table-cell">{t.card_last4 ? <>••{t.card_last4}{t.cardholder ? ` · ${t.cardholder}` : ''}</> : '—'}</Td>
                  <Td className="hidden text-muted md:table-cell">{t.category_name ?? '—'}</Td>
                  <Td right><Money cents={t.amount} signed className={t.status === 'declined' ? 'text-muted line-through' : ''} /></Td>
                </Tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>

      <PayModal
        open={payOpen}
        onClose={() => setPayOpen(false)}
        credit={credit}
        owed={owed}
        sources={accounts.data.filter((a) => a.type === 'checking' && a.status === 'open')}
        onPaid={(p) => {
          setPayOpen(false);
          setNotice(p.status === 'pending_approval' ? `Payment of ${money(p.amount)} submitted for approval.` : `Payment of ${money(p.amount)} sent.`);
          accounts.reload(); tx.reload();
        }}
      />
    </>
  );
}

function PayModal({ open, onClose, credit, owed, sources, onPaid }: { open: boolean; onClose: () => void; credit: Account; owed: number; sources: Account[]; onPaid: (p: Payment) => void }) {
  const { busy, error, run, setError } = useAction();
  const [from, setFrom] = useState('');
  const [amount, setAmount] = useState<number | null>(owed);
  const [k, setK] = useState(0);
  useEffect(() => {
    if (!open) return;
    setAmount(owed); setK((x) => x + 1); setError(null);
    setFrom((f) => f || sources[0]?.id || '');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);
  const src = sources.find((a) => a.id === from);

  const submit = () => run(async () => {
    if (!from) throw new Error('Choose an account to pay from');
    if (!amount) throw new Error('Enter an amount');
    const p = await post<Payment>('/payments', { account_id: from, to_account_id: credit.id, rail: 'book', amount, memo: 'IO Credit payment' });
    onPaid(p);
  });

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Pay credit balance"
      footer={<><Button onClick={onClose}>Cancel</Button><Button variant="primary" disabled={busy} onClick={submit}>{busy ? 'Paying…' : `Pay ${amount ? money(amount) : ''}`}</Button></>}
    >
      <Field label="Pay from" hint={src ? `${money(src.balance.available)} available` : undefined}>
        <Select value={from} onChange={(e) => setFrom(e.target.value)}>
          {sources.map((a) => <option key={a.id} value={a.id}>{a.name} ••{a.account_number.slice(-4)}</option>)}
        </Select>
      </Field>
      <Field label="Amount" hint={`Balance owed ${money(owed)}`}>
        <MoneyInput key={k} value={amount} onChange={setAmount} />
      </Field>
      <div className="text-[13px] text-muted">Internal transfer — settles instantly to {credit.name}.</div>
      <ErrorNote error={error} />
    </Modal>
  );
}
