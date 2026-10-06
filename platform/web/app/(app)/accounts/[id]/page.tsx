'use client';

import Link from 'next/link';
import { useParams } from 'next/navigation';
import { useState } from 'react';
import { download, patch, useApi } from '@/lib/api';
import { can, useSession } from '@/lib/session';
import { date, money, shortDate, titleCase } from '@/lib/format';
import type { Account, Transaction } from '@/lib/types';
import { Badge, Button, Card, Empty, ErrorNote, Field, Input, Loading, Modal, Money, PageHeader, Stat, StatusBadge, Table, Tabs, Td, Th, Tr, useAction } from '@/components/ui';
import { TxDrawer, TxTable } from '../../transactions/_tx';

interface Statement {
  account: { name: string; type: string; account_number: string; routing_number: string };
  month: string;
  opening_balance: number;
  closing_balance: number;
  total_in: number;
  total_out: number;
  transactions: { posted_at: string; counterparty_name: string; description: string; kind: string; amount: number }[];
}

const monthLabel = (m: string) => new Date(m + '-15T12:00:00Z').toLocaleDateString('en-US', { month: 'long', year: 'numeric', timeZone: 'UTC' });

export default function AccountDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { user } = useSession();
  const acct = useApi<Account>(`/accounts/${id}`);
  const tx = useApi<{ total: number; rows: Transaction[] }>(`/transactions?account_id=${id}&limit=25`);
  const [tab, setTab] = useState<'activity' | 'statements' | 'details'>('activity');
  const [open, setOpen] = useState<Transaction | null>(null);
  const [renaming, setRenaming] = useState(false);
  const act = useAction();

  if (acct.error) return <><BackLink /><ErrorNote error={acct.error} /></>;
  const a = acct.data;
  if (!a) return <Loading />;
  const isCredit = a.type === 'credit';
  const admin = can.admin(user.role);

  const setStatus = async (status: Account['status']) => {
    if (status === 'closed' && !confirm(`Close ${a.name}? This cannot be undone.`)) return;
    const r = await act.run(() => patch(`/accounts/${a.id}`, { status }));
    if (r) acct.reload();
  };

  return (
    <>
      <BackLink />
      <PageHeader
        title={a.name}
        subtitle={
          <span className="flex flex-wrap items-center gap-2">
            {titleCase(a.type)} ••{a.account_number.slice(-4)}
            {a.status !== 'open' && <StatusBadge status={a.status} />}
            {a.apy_bps > 0 && <Badge tone="green">{(a.apy_bps / 100).toFixed(2)}% APY</Badge>}
          </span>
        }
        actions={
          admin && a.status !== 'closed' ? (
            <>
              <Button size="sm" onClick={() => setRenaming(true)}>Rename</Button>
              {a.status === 'open' ? (
                <Button size="sm" disabled={act.busy} onClick={() => setStatus('frozen')}>Freeze</Button>
              ) : (
                <Button size="sm" disabled={act.busy} onClick={() => setStatus('open')}>Unfreeze</Button>
              )}
              <Button size="sm" variant="danger" disabled={act.busy} onClick={() => setStatus('closed')}>Close account</Button>
            </>
          ) : undefined
        }
      />
      {act.error && <div className="mb-4"><ErrorNote error={act.error} /></div>}
      {a.status === 'frozen' && (
        <div className="mb-4 rounded-lg border border-brand-500/30 bg-brand-500/5 px-3 py-2 text-[13px]">
          This account is frozen. Outgoing payments are blocked until it is unfrozen.
        </div>
      )}

      <div className="mb-6 grid gap-4 sm:grid-cols-3">
        {isCredit ? (
          <>
            <Stat label="Balance owed" value={money(-a.balance.current)} />
            <Stat label="Pending" value={money(-a.balance.pending)} />
            <Stat label="Available credit" value={money(a.balance.available)} hint={`of ${money(a.credit_limit)} limit`} />
          </>
        ) : (
          <>
            <Stat label="Current balance" value={money(a.balance.current)} />
            <Stat label="Pending" value={<Money cents={a.balance.pending} signed />} />
            <Stat label="Available" value={money(a.balance.available)} />
          </>
        )}
      </div>

      <Tabs
        value={tab}
        onChange={setTab}
        tabs={[
          { value: 'activity', label: 'Activity' },
          { value: 'statements', label: 'Statements' },
          { value: 'details', label: 'Account details' },
        ]}
      />

      {tab === 'activity' && (
        <Card
          title="Recent transactions"
          padded={false}
          actions={tx.data && tx.data.total > 25 ? <Link href={`/transactions?account_id=${a.id}`} className="text-[13px] text-brand-600">View all {tx.data.total}</Link> : undefined}
        >
          <ErrorNote error={tx.error} />
          <TxTable rows={tx.data?.rows} onOpen={setOpen} showAccount={false} emptyHint="Activity on this account will show up here." />
        </Card>
      )}
      {tab === 'statements' && <Statements account={a} />}
      {tab === 'details' && <Details account={a} />}

      <TxDrawer tx={open} onClose={() => setOpen(null)} onChanged={() => { tx.reload(); acct.reload(); }} />
      <RenameModal open={renaming} account={a} onClose={() => setRenaming(false)} onDone={acct.reload} />
    </>
  );
}

function BackLink() {
  return <Link href="/accounts" className="mb-3 inline-block text-[13px] text-muted hover:text-ink">← Accounts</Link>;
}

function SecretNumber({ label, value }: { label: string; value: string }) {
  const [show, setShow] = useState(false);
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      setShow(true);
    }
  };
  return (
    <div className="flex flex-wrap items-center justify-between gap-2 py-2.5">
      <span className="text-muted">{label}</span>
      <span className="flex items-center gap-2">
        <span className="tabular font-medium">{show ? value : `••••${value.slice(-4)}`}</span>
        <Button size="sm" variant="ghost" onClick={() => setShow(!show)}>{show ? 'Hide' : 'Show'}</Button>
        <Button size="sm" variant="ghost" onClick={copy}>{copied ? 'Copied' : 'Copy'}</Button>
      </span>
    </div>
  );
}

function Details({ account: a }: { account: Account }) {
  return (
    <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
      <Card title="Account & routing numbers">
        <div className="divide-y divide-line">
          <SecretNumber label="Account number" value={a.account_number} />
          <div className="flex flex-wrap items-center justify-between gap-2 py-2.5">
            <span className="text-muted">Routing number (ACH & wire)</span>
            <span className="flex items-center gap-2">
              <span className="tabular font-medium">{a.routing_number}</span>
              <Button size="sm" variant="ghost" onClick={() => navigator.clipboard?.writeText(a.routing_number).catch(() => {})}>Copy</Button>
            </span>
          </div>
        </div>
        <p className="mt-3 text-[12px] text-muted">Share these details to receive ACH and domestic wire payments into this account.</p>
      </Card>
      <Card title="About this account">
        <dl className="divide-y divide-line">
          {[
            ['Type', titleCase(a.type)],
            ['Status', <StatusBadge key="s" status={a.status} />],
            ...(a.apy_bps > 0 ? [['APY', `${(a.apy_bps / 100).toFixed(2)}%`]] : []),
            ...(a.type === 'credit' ? [['Credit limit', money(a.credit_limit)]] : []),
            ['Opened', date(a.created_at)],
          ].map(([k, v], i) => (
            <div key={i} className="flex justify-between gap-4 py-2.5">
              <dt className="text-muted">{k}</dt>
              <dd className="text-right font-medium">{v}</dd>
            </div>
          ))}
        </dl>
      </Card>
    </div>
  );
}

function Statements({ account: a }: { account: Account }) {
  const months = useApi<string[]>(`/accounts/${a.id}/statements`);
  const [month, setMonth] = useState<string | null>(null);
  const st = useApi<Statement>(month ? `/accounts/${a.id}/statements/${month}` : null);
  const csv = (m: string) => download(`/accounts/${a.id}/statements/${m}?format=csv`, `statement-${a.account_number.slice(-4)}-${m}.csv`);

  if (month) {
    const s = st.data && st.data.month === month ? st.data : undefined;
    return (
      <Card
        title={<span><button onClick={() => setMonth(null)} className="mr-2 text-muted hover:text-ink">←</button>{monthLabel(month)} statement</span>}
        actions={<Button size="sm" onClick={() => csv(month)}>Download CSV</Button>}
        padded={false}
      >
        <ErrorNote error={st.error} />
        {!s ? <Loading /> : (
          <>
            <div className="grid grid-cols-2 gap-px bg-line sm:grid-cols-4">
              {[
                ['Opening balance', <Money key="o" cents={s.opening_balance} />],
                ['Money in', <Money key="i" cents={s.total_in} signed />],
                ['Money out', <Money key="u" cents={s.total_out} />],
                ['Closing balance', <Money key="c" cents={s.closing_balance} />],
              ].map(([k, v], i) => (
                <div key={i} className="bg-surface px-5 py-4">
                  <div className="text-[12px] text-muted">{k}</div>
                  <div className="mt-1 text-lg font-semibold">{v}</div>
                </div>
              ))}
            </div>
            {!s.transactions.length ? <Empty title="No posted transactions this month" /> : (
              <Table className="border-t border-line">
                <thead>
                  <tr><Th className="w-24">Date</Th><Th>Description</Th><Th>Type</Th><Th right>Amount</Th></tr>
                </thead>
                <tbody>
                  {s.transactions.map((t, i) => (
                    <Tr key={i}>
                      <Td className="whitespace-nowrap text-muted">{shortDate(t.posted_at)}</Td>
                      <Td className="min-w-48">
                        <div className="font-medium">{t.counterparty_name}</div>
                        <div className="text-[12px] text-muted">{t.description}</div>
                      </Td>
                      <Td className="whitespace-nowrap text-[13px] text-muted">{titleCase(t.kind)}</Td>
                      <Td right className="whitespace-nowrap"><Money cents={t.amount} signed /></Td>
                    </Tr>
                  ))}
                </tbody>
              </Table>
            )}
          </>
        )}
      </Card>
    );
  }

  return (
    <Card title="Monthly statements" padded={false}>
      <ErrorNote error={months.error} />
      {!months.data ? (months.error ? null : <Loading />) : !months.data.length ? (
        <Empty title="No statements yet" hint="Statements are generated after each full calendar month." />
      ) : (
        <ul className="divide-y divide-line">
          {months.data.map((m) => (
            <li key={m} className="flex items-center justify-between gap-3 px-5 py-3 hover:bg-surface-2">
              <button className="flex-1 text-left font-medium" onClick={() => setMonth(m)}>{monthLabel(m)}</button>
              <div className="flex gap-2">
                <Button size="sm" variant="ghost" onClick={() => setMonth(m)}>View</Button>
                <Button size="sm" variant="ghost" onClick={() => csv(m)}>CSV</Button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

function RenameModal({ open, account, onClose, onDone }: { open: boolean; account: Account; onClose: () => void; onDone: () => void }) {
  const [name, setName] = useState(account.name);
  const act = useAction();
  const submit = async () => {
    const r = await act.run(() => patch(`/accounts/${account.id}`, { name: name.trim() }));
    if (r) {
      onDone();
      onClose();
    }
  };
  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Rename account"
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={!name.trim() || act.busy} onClick={submit}>Save</Button>
        </>
      }
    >
      <Field label="Account name">
        <Input autoFocus value={name} onChange={(e) => setName(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && name.trim() && submit()} />
      </Field>
      <ErrorNote error={act.error} />
    </Modal>
  );
}
