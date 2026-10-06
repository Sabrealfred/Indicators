'use client';

import { useEffect, useMemo, useState } from 'react';
import { post, useApi } from '@/lib/api';
import { can, useSession } from '@/lib/session';
import { date, money } from '@/lib/format';
import type { Account, Payment, Transaction } from '@/lib/types';
import { Button, Card, Empty, ErrorNote, Field, Loading, Modal, Money, MoneyInput, PageHeader, Select, Stat, Table, Td, Th, Tr, cx, useAction } from '@/components/ui';

type Direction = 'in' | 'out';

export default function TreasuryPage() {
  const { user } = useSession();
  const accounts = useApi<Account[]>('/accounts');
  const treasury = accounts.data?.find((a) => a.type === 'treasury' && a.status !== 'closed');
  const interest = useApi<{ total: number; rows: Transaction[] }>(treasury ? `/transactions?account_id=${treasury.id}&kind=interest&limit=500` : null);
  const [move, setMove] = useState<Direction | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const rows = useMemo(() => (interest.data?.rows ?? []).filter((t) => t.status === 'posted' || t.status === 'pending'), [interest.data]);
  const monthly = useMemo(() => {
    const m = new Map<string, number>();
    for (const t of rows) m.set(t.created_at.slice(0, 7), (m.get(t.created_at.slice(0, 7)) ?? 0) + t.amount);
    return [...m.entries()].sort(([a], [b]) => a.localeCompare(b)).slice(-12);
  }, [rows]);

  if (accounts.error) return <><PageHeader title="Treasury" /><ErrorNote error={accounts.error} /></>;
  if (!accounts.data) return <Loading />;
  if (!treasury) {
    return (
      <>
        <PageHeader title="Treasury" />
        <Card><Empty title="No treasury account" hint="Open a treasury account from Accounts to earn yield on idle cash." /></Card>
      </>
    );
  }

  const apy = treasury.apy_bps / 100;
  const bal = treasury.balance.current;
  const annual = Math.round((bal * treasury.apy_bps) / 10000);
  const totalInterest = rows.reduce((s, t) => s + t.amount, 0);
  const ytd = rows.filter((t) => t.created_at.slice(0, 4) === new Date().toISOString().slice(0, 4)).reduce((s, t) => s + t.amount, 0);
  const checking = accounts.data.filter((a) => a.type === 'checking' && a.status === 'open');
  const maxM = Math.max(1, ...monthly.map(([, v]) => v));
  const canMove = can.moveMoney(user.role);

  return (
    <>
      <PageHeader
        title="Treasury"
        subtitle={`${treasury.name} · ••${treasury.account_number.slice(-4)}`}
        actions={canMove && (
          <>
            <Button onClick={() => setMove('out')}>Withdraw</Button>
            <Button variant="primary" onClick={() => setMove('in')}>Move money in</Button>
          </>
        )}
      />

      {notice && <div className="mb-4 rounded-lg border border-emerald-500/30 bg-emerald-500/5 px-3 py-2 text-[13px] text-pos">{notice}</div>}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label="Balance" value={money(bal)} hint={treasury.balance.pending ? `${money(treasury.balance.pending)} pending` : 'Available immediately'} />
        <Stat label="Current yield" value={`${apy.toFixed(2)}% APY`} hint="Variable rate, accrues daily" />
        <Stat label="Est. annual yield" value={money(annual)} tone="pos" hint={`≈ ${money(Math.round(annual / 12))} / month at today’s balance`} />
        <Stat label="Interest earned" value={money(totalInterest)} tone="pos" hint={`${money(ytd)} this year`} />
      </div>

      <div className="mt-6 grid gap-6 lg:grid-cols-3">
        <Card title="Monthly interest" className="lg:col-span-2">
          {!interest.data ? <Loading /> : !monthly.length ? <Empty title="No interest paid yet" hint="Interest is credited at month-end." /> : (
            <div className="flex h-[180px] items-end gap-2 sm:gap-4">
              {monthly.map(([m, v]) => (
                <div key={m} className="flex flex-1 flex-col items-center gap-1">
                  <div className="text-[11px] tabular text-muted">{money(v).replace(/\.\d+$/, '')}</div>
                  <div title={money(v)} className="w-full max-w-10 rounded-t bg-emerald-500/80" style={{ height: Math.max(3, (v / maxM) * 130) }} />
                  <div className="text-[12px] text-muted">{new Date(m + '-15').toLocaleDateString('en-US', { month: 'short' })}</div>
                </div>
              ))}
            </div>
          )}
        </Card>
        <Card title="How it works">
          <ul className="space-y-2.5 text-[13px] text-muted">
            <li>Interest accrues daily on your full balance and is credited on the 1st of each month.</li>
            <li>Move money to and from checking instantly — no lockups or minimums.</li>
            <li className="rounded-lg bg-surface-2 p-3 text-[12px]">
              This demo treasury account is simulated. It is not an investment product, is not a security, and no real funds or yields are involved.
            </li>
          </ul>
        </Card>
      </div>

      <Card title="Interest history" className="mt-6" padded={false} actions={<span className="text-[13px] text-muted">Total <Money cents={totalInterest} className="font-medium text-ink" /></span>}>
        <ErrorNote error={interest.error} />
        {!interest.data ? <Loading /> : !rows.length ? <Empty title="No interest payments yet" /> : (
          <Table>
            <thead><tr><Th>Date</Th><Th>Description</Th><Th right>Amount</Th></tr></thead>
            <tbody>
              {rows.map((t) => (
                <Tr key={t.id}>
                  <Td className="whitespace-nowrap text-muted">{date(t.posted_at ?? t.created_at)}</Td>
                  <Td>{t.description || t.counterparty_name}</Td>
                  <Td right><Money cents={t.amount} signed /></Td>
                </Tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>

      <MoveModal
        direction={move}
        onClose={() => setMove(null)}
        onDirection={setMove}
        treasury={treasury}
        checking={checking}
        onDone={(p) => {
          const verb = move === 'in' ? 'into' : 'out of';
          setMove(null);
          setNotice(p.status === 'pending_approval' ? `Transfer of ${money(p.amount)} submitted for approval.` : `Moved ${money(p.amount)} ${verb} ${treasury.name}.`);
          accounts.reload(); interest.reload();
        }}
      />
    </>
  );
}

function MoveModal({ direction, onClose, onDirection, treasury, checking, onDone }: {
  direction: Direction | null; onClose: () => void; onDirection: (d: Direction) => void; treasury: Account; checking: Account[]; onDone: (p: Payment) => void;
}) {
  const { busy, error, run, setError } = useAction();
  const [other, setOther] = useState('');
  const [amount, setAmount] = useState<number | null>(null);
  const [k, setK] = useState(0);
  const open = direction !== null;
  useEffect(() => {
    if (!open) return;
    setAmount(null); setK((x) => x + 1); setError(null);
    setOther((o) => o || checking[0]?.id || '');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const otherAcct = checking.find((a) => a.id === other);
  const source = direction === 'in' ? otherAcct : treasury;

  const submit = () => run(async () => {
    if (!other) throw new Error('Choose a checking account');
    if (!amount) throw new Error('Enter an amount');
    const body = direction === 'in'
      ? { account_id: other, to_account_id: treasury.id, rail: 'book', amount, memo: 'Transfer to treasury' }
      : { account_id: treasury.id, to_account_id: other, rail: 'book', amount, memo: 'Transfer from treasury' };
    onDone(await post<Payment>('/payments', body));
  });

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Move money"
      footer={<><Button onClick={onClose}>Cancel</Button><Button variant="primary" disabled={busy} onClick={submit}>{busy ? 'Moving…' : 'Transfer'}</Button></>}
    >
      <div className="grid grid-cols-2 rounded-lg bg-surface-2 p-1">
        {(['in', 'out'] as Direction[]).map((d) => (
          <button key={d} type="button" onClick={() => onDirection(d)} className={cx('rounded-md py-1.5 font-medium', direction === d ? 'bg-surface shadow-sm' : 'text-muted')}>
            {d === 'in' ? 'Into treasury' : 'Out of treasury'}
          </button>
        ))}
      </div>
      <div className="grid items-end gap-3 sm:grid-cols-[1fr_auto_1fr]">
        {direction === 'in' ? (
          <>
            <Field label="From">
              <Select value={other} onChange={(e) => setOther(e.target.value)}>{checking.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</Select>
            </Field>
            <div className="hidden pb-2 text-muted sm:block">→</div>
            <Field label="To"><div className="flex h-9 items-center rounded-lg border border-line bg-surface-2 px-3">{treasury.name}</div></Field>
          </>
        ) : (
          <>
            <Field label="From"><div className="flex h-9 items-center rounded-lg border border-line bg-surface-2 px-3">{treasury.name}</div></Field>
            <div className="hidden pb-2 text-muted sm:block">→</div>
            <Field label="To">
              <Select value={other} onChange={(e) => setOther(e.target.value)}>{checking.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</Select>
            </Field>
          </>
        )}
      </div>
      <Field label="Amount" hint={source ? `${money(source.balance.available)} available in ${source.name}` : undefined}>
        <MoneyInput key={k} value={amount} onChange={setAmount} autoFocus />
      </Field>
      <div className="text-[13px] text-muted">Internal book transfer — settles instantly.</div>
      <ErrorNote error={error} />
    </Modal>
  );
}
