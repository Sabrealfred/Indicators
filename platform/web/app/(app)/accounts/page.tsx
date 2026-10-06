'use client';

import Link from 'next/link';
import { useState } from 'react';
import { post, useApi } from '@/lib/api';
import { can, useSession } from '@/lib/session';
import { money } from '@/lib/format';
import type { Account } from '@/lib/types';
import { Badge, Button, Empty, ErrorNote, Field, Input, Loading, Modal, MoneyInput, PageHeader, Select, StatusBadge, Stat, useAction } from '@/components/ui';

const TYPE_LABEL: Record<Account['type'], string> = { checking: 'Checking', savings: 'Savings', treasury: 'Treasury', credit: 'Credit' };

export default function AccountsPage() {
  const { user } = useSession();
  const accounts = useApi<Account[]>('/accounts');
  const [opening, setOpening] = useState(false);
  const list = accounts.data ?? [];
  const deposit = list.filter((a) => a.type !== 'credit' && a.status !== 'closed');
  const credit = list.filter((a) => a.type === 'credit' && a.status !== 'closed');
  const closed = list.filter((a) => a.status === 'closed');
  const totalCash = deposit.reduce((s, a) => s + a.balance.current, 0);
  const totalAvail = deposit.reduce((s, a) => s + a.balance.available, 0);
  const owed = credit.reduce((s, a) => s - a.balance.current, 0);

  return (
    <>
      <PageHeader
        title="Accounts"
        subtitle="Checking, savings, treasury and credit accounts held at LedgerBank."
        actions={can.admin(user.role) && <Button variant="primary" onClick={() => setOpening(true)}>Open account</Button>}
      />
      <ErrorNote error={accounts.error} />
      {!accounts.data ? (
        accounts.error ? null : <Loading />
      ) : !list.length ? (
        <Empty title="No accounts yet" hint="Open your first account to get started." />
      ) : (
        <>
          <div className="mb-6 grid gap-4 sm:grid-cols-3">
            <Stat label="Total balance" value={money(totalCash)} hint={`${deposit.length} deposit account${deposit.length === 1 ? '' : 's'}`} />
            <Stat label="Available" value={money(totalAvail)} hint={totalCash !== totalAvail ? `${money(totalCash - totalAvail)} pending` : 'Nothing pending'} />
            <Stat label="Credit owed" value={money(owed)} hint={credit.length ? `${credit.length} credit account${credit.length === 1 ? '' : 's'}` : 'No credit accounts'} />
          </div>
          <Section title="Deposit accounts" accounts={deposit} />
          {credit.length > 0 && <Section title="Credit" accounts={credit} />}
          {closed.length > 0 && <Section title="Closed" accounts={closed} />}
        </>
      )}
      <OpenAccountModal open={opening} onClose={() => setOpening(false)} onDone={accounts.reload} />
    </>
  );
}

function Section({ title, accounts }: { title: string; accounts: Account[] }) {
  if (!accounts.length) return null;
  return (
    <div className="mb-8">
      <h2 className="mb-3 text-[12px] font-semibold uppercase tracking-wider text-muted">{title}</h2>
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {accounts.map((a) => <AccountCard key={a.id} a={a} />)}
      </div>
    </div>
  );
}

function AccountCard({ a }: { a: Account }) {
  const isCredit = a.type === 'credit';
  const owed = -a.balance.current;
  return (
    <Link href={`/accounts/${a.id}`} className="block rounded-xl border border-line bg-surface p-5 transition-colors hover:border-brand-500/40 hover:bg-surface-2/40">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="truncate font-semibold">{a.name}</div>
          <div className="text-[12px] text-muted">{TYPE_LABEL[a.type]} ••{a.account_number.slice(-4)}</div>
        </div>
        <div className="flex shrink-0 gap-1">
          {a.status !== 'open' && <StatusBadge status={a.status} />}
          {a.apy_bps > 0 && <Badge tone="green">{(a.apy_bps / 100).toFixed(2)}% APY</Badge>}
        </div>
      </div>
      <div className="mt-5 text-[12px] text-muted">{isCredit ? 'Balance owed' : 'Current balance'}</div>
      <div className="text-2xl font-semibold tabular tracking-tight">{money(isCredit ? owed : a.balance.current)}</div>
      <div className="mt-3 flex justify-between border-t border-line pt-3 text-[13px]">
        <span className="text-muted">{isCredit ? 'Available credit' : 'Available'}</span>
        <span className="tabular font-medium">{money(a.balance.available)}</span>
      </div>
      {isCredit && (
        <>
          <div className="mt-1 flex justify-between text-[13px]">
            <span className="text-muted">Credit limit</span>
            <span className="tabular font-medium">{money(a.credit_limit)}</span>
          </div>
          <div className="mt-2 h-1.5 rounded bg-surface-2">
            <div className="h-1.5 rounded bg-brand-500" style={{ width: `${Math.min(100, a.credit_limit ? (Math.max(0, owed) / a.credit_limit) * 100 : 0)}%` }} />
          </div>
        </>
      )}
    </Link>
  );
}

function OpenAccountModal({ open, onClose, onDone }: { open: boolean; onClose: () => void; onDone: () => void }) {
  const [name, setName] = useState('');
  const [type, setType] = useState<Account['type']>('checking');
  const [apy, setApy] = useState('');
  const [limit, setLimit] = useState<number | null>(null);
  const act = useAction();
  const apyNum = apy.trim() === '' ? 0 : Number(apy);
  const apyValid = Number.isFinite(apyNum) && apyNum >= 0 && apyNum <= 20;
  const valid = name.trim() && (type === 'savings' || type === 'treasury' ? apyValid : true) && (type === 'credit' ? limit != null && limit > 0 : true);

  const close = () => {
    setName('');
    setType('checking');
    setApy('');
    setLimit(null);
    act.setError(null);
    onClose();
  };

  const submit = async () => {
    const body: Record<string, unknown> = { name: name.trim(), type };
    if (type === 'savings' || type === 'treasury') body.apy_bps = Math.round(apyNum * 100);
    if (type === 'credit') body.credit_limit = limit;
    const r = await act.run(() => post('/accounts', body));
    if (r) {
      onDone();
      close();
    }
  };

  return (
    <Modal
      open={open}
      onClose={close}
      title="Open account"
      footer={
        <>
          <Button onClick={close}>Cancel</Button>
          <Button variant="primary" disabled={!valid || act.busy} onClick={submit}>{act.busy ? 'Opening…' : 'Open account'}</Button>
        </>
      }
    >
      <Field label="Account name">
        <Input autoFocus value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Tax reserve" />
      </Field>
      <Field label="Type">
        <Select value={type} onChange={(e) => setType(e.target.value as Account['type'])}>
          <option value="checking">Checking</option>
          <option value="savings">Savings</option>
          <option value="treasury">Treasury</option>
          <option value="credit">Credit</option>
        </Select>
      </Field>
      {(type === 'savings' || type === 'treasury') && (
        <Field label="APY (%)" hint="Annual percentage yield, e.g. 4.25" error={apyValid ? null : 'Enter a rate between 0 and 20%'}>
          <Input inputMode="decimal" value={apy} onChange={(e) => setApy(e.target.value)} placeholder="4.00" />
        </Field>
      )}
      {type === 'credit' && (
        <Field label="Credit limit">
          <MoneyInput value={limit} onChange={setLimit} placeholder="50,000.00" />
        </Field>
      )}
      <ErrorNote error={act.error} />
    </Modal>
  );
}
