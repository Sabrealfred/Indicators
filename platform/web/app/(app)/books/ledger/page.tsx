'use client';

import { Suspense, useState } from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { patch, post, useApi } from '@/lib/api';
import { shortDate } from '@/lib/format';
import type { GlAccount } from '@/lib/types';
import { Badge, Button, Card, Empty, ErrorNote, Field, Input, Loading, Modal, PageHeader, Select, Table, Td, Th, cx, useAction } from '@/components/ui';
import { Amt, GL_TYPES, GL_TYPE_LABEL, SourceBadge, acct, csvAmt, downloadCsv } from '../_shared';

interface LedgerDetail {
  account: Omit<GlAccount, 'balance' | 'debits' | 'credits'>;
  from: string;
  to: string;
  opening_balance: number;
  closing_balance: number;
  lines: { entry_id: string; date: string; memo: string; source_type: string; source_id: string | null; amount: number; description: string | null; debit: number; credit: number; balance: number }[];
}

function AddAccountModal({ open, onClose, onDone }: { open: boolean; onClose: () => void; onDone: (id: string) => void }) {
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [type, setType] = useState<GlAccount['type']>('expense');
  const a = useAction();
  const codeOk = /^\d{4}$/.test(code);
  const submit = async () => {
    const r = await a.run(() => post<{ id: string }>('/books/accounts', { code, name: name.trim(), type }));
    if (r) {
      setCode(''); setName('');
      onDone(r.id);
    }
  };
  return (
    <Modal open={open} onClose={onClose} title="Add account" footer={
      <>
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        <Button variant="primary" disabled={!codeOk || !name.trim() || a.busy} onClick={submit}>{a.busy ? 'Adding…' : 'Add account'}</Button>
      </>
    }>
      <div className="grid grid-cols-3 gap-3">
        <Field label="Code" error={code && !codeOk ? '4 digits' : null}>
          <Input value={code} inputMode="numeric" maxLength={4} placeholder="6250" onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} autoFocus />
        </Field>
        <div className="col-span-2">
          <Field label="Type">
            <Select value={type} onChange={(e) => setType(e.target.value as GlAccount['type'])}>
              {GL_TYPES.map((t) => <option key={t} value={t}>{GL_TYPE_LABEL[t]}</option>)}
            </Select>
          </Field>
        </div>
      </div>
      <Field label="Name"><Input value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Recruiting" /></Field>
      <p className="text-[12px] text-muted">Convention: 1xxx assets, 2xxx liabilities, 3xxx equity, 4xxx revenue, 5xxx–9xxx expenses.</p>
      <ErrorNote error={a.error} />
    </Modal>
  );
}

function EditAccountModal({ account, onClose, onDone }: { account: GlAccount | null; onClose: () => void; onDone: () => void }) {
  const [name, setName] = useState(account?.name ?? '');
  const a = useAction();
  if (!account) return null;
  const save = async (body: { name?: string; archived?: boolean }) => {
    const r = await a.run(() => patch(`/books/accounts/${account.id}`, body));
    if (r) onDone();
  };
  return (
    <Modal open onClose={onClose} title={`Edit ${account.code} · ${account.name}`} footer={
      <>
        {!account.system_key && (
          account.archived ? (
            <Button className="mr-auto" disabled={a.busy} onClick={() => save({ archived: false })}>Unarchive</Button>
          ) : (
            <Button variant="danger" className="mr-auto" disabled={a.busy} onClick={() => confirm(`Archive ${account.code} ${account.name}? It will be hidden from pickers; history is kept.`) && save({ archived: true })}>Archive</Button>
          )
        )}
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        <Button variant="primary" disabled={!name.trim() || name === account.name || a.busy} onClick={() => save({ name: name.trim() })}>Save</Button>
      </>
    }>
      <Field label="Name"><Input value={name} onChange={(e) => setName(e.target.value)} autoFocus /></Field>
      {account.system_key && <p className="text-[12px] text-muted">System account (used for automatic postings) — it can be renamed but not archived.</p>}
      <ErrorNote error={a.error} />
    </Modal>
  );
}

function LedgerView({ id, accounts }: { id: string; accounts?: GlAccount[] }) {
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const q = new URLSearchParams();
  if (from) q.set('from', from);
  if (to) q.set('to', to);
  const r = useApi<LedgerDetail>(`/books/accounts/${id}/ledger${q.toString() ? `?${q}` : ''}`);
  const d = r.data;
  const meta = accounts?.find((a) => a.id === id);

  return (
    <Card padded={false}>
      <div className="flex flex-wrap items-start justify-between gap-3 border-b border-line px-5 py-4">
        <div>
          <div className="text-[12px] uppercase tracking-wide text-muted">General ledger</div>
          <h2 className="text-lg font-semibold">{d ? `${d.account.code} · ${d.account.name}` : meta ? `${meta.code} · ${meta.name}` : '…'}</h2>
          {d && <div className="text-[13px] capitalize text-muted">{d.account.type}{d.account.subtype ? ` · ${d.account.subtype.replace('_', ' ')}` : ''}</div>}
        </div>
        <div className="flex flex-wrap items-end gap-2">
          <Field label="From"><Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="w-38" /></Field>
          <Field label="To"><Input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="w-38" /></Field>
          {(from || to) && <Button size="sm" variant="ghost" onClick={() => { setFrom(''); setTo(''); }}>Clear</Button>}
          {d && (
            <Button size="sm" onClick={() => downloadCsv(`ledger_${d.account.code}.csv`, [
              ['Date', 'Memo', 'Source', 'Debit', 'Credit', 'Balance'],
              ['', 'Opening balance', '', '', '', csvAmt(d.opening_balance)],
              ...d.lines.map((l) => [l.date, l.description || l.memo, l.source_type, l.debit ? csvAmt(l.debit) : '', l.credit ? csvAmt(l.credit) : '', csvAmt(l.balance)]),
              ['', 'Closing balance', '', '', '', csvAmt(d.closing_balance)],
            ])}>Export CSV</Button>
          )}
        </div>
      </div>
      <ErrorNote error={r.error} />
      {!d ? (!r.error && <Loading />) : (
        <Table>
          <thead>
            <tr><Th className="w-24">Date</Th><Th>Memo</Th><Th>Source</Th><Th right>Debit</Th><Th right>Credit</Th><Th right>Balance</Th></tr>
          </thead>
          <tbody>
            <tr className="bg-surface-2">
              <Td className="py-2 text-muted">{d.from !== '0000-00-00' ? shortDate(d.from) : ''}</Td>
              <Td className="py-2 font-medium" colSpan={4}>Opening balance</Td>
              <Td right className="py-2 font-medium"><Amt cents={d.opening_balance} /></Td>
            </tr>
            {d.lines.length === 0 && (
              <tr><Td colSpan={6} className="py-8 text-center text-muted">No activity in this period</Td></tr>
            )}
            {d.lines.map((l, i) => (
              <tr key={`${l.entry_id}-${i}`} className="hover:bg-surface-2">
                <Td className="whitespace-nowrap py-2 text-muted">{shortDate(l.date)}</Td>
                <Td className="max-w-[320px] py-2"><div className="truncate" title={l.description || l.memo}>{l.description || l.memo}</div></Td>
                <Td className="py-2"><SourceBadge type={l.source_type} /></Td>
                <Td right className="py-2">{l.debit ? acct(l.debit) : ''}</Td>
                <Td right className="py-2">{l.credit ? acct(l.credit) : ''}</Td>
                <Td right className="py-2"><Amt cents={l.balance} /></Td>
              </tr>
            ))}
            <tr className="bg-surface-2 font-semibold">
              <Td className="py-2.5 text-muted">{d.to !== '9999-12-31' ? shortDate(d.to) : ''}</Td>
              <Td className="py-2.5" colSpan={2}>Closing balance</Td>
              <Td right className="py-2.5">{acct(d.lines.reduce((s, l) => s + l.debit, 0))}</Td>
              <Td right className="py-2.5">{acct(d.lines.reduce((s, l) => s + l.credit, 0))}</Td>
              <Td right className="py-2.5"><Amt cents={d.closing_balance} bold /></Td>
            </tr>
          </tbody>
        </Table>
      )}
    </Card>
  );
}

function LedgerInner() {
  const params = useSearchParams();
  const router = useRouter();
  const path = usePathname();
  const selected = params.get('account');
  const coa = useApi<GlAccount[]>('/books/accounts');
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<GlAccount | null>(null);
  const [showArchived, setShowArchived] = useState(false);
  const [filter, setFilter] = useState('');

  const select = (id: string | null) => router.replace(id ? `${path}?account=${id}` : path, { scroll: false });
  const rows = (coa.data ?? []).filter((a) => (showArchived || !a.archived || a.id === selected) && (!filter || `${a.code} ${a.name}`.toLowerCase().includes(filter.toLowerCase())));

  return (
    <>
      <PageHeader
        title="Chart of accounts"
        subtitle="Every account in your general ledger. Select one to see its detail."
        actions={<Button variant="primary" onClick={() => setAdding(true)}>Add account</Button>}
      />
      <ErrorNote error={coa.error} />
      <div className={cx('grid gap-6', selected && 'xl:grid-cols-[minmax(0,5fr)_minmax(0,7fr)]')}>
        <Card padded={false}>
          <div className="flex flex-wrap items-center gap-3 border-b border-line px-4 py-3">
            <Input placeholder="Search code or name" value={filter} onChange={(e) => setFilter(e.target.value)} className="max-w-60" />
            <label className="flex items-center gap-2 text-[13px] text-muted">
              <input type="checkbox" checked={showArchived} onChange={(e) => setShowArchived(e.target.checked)} /> Show archived
            </label>
          </div>
          {!coa.data ? (!coa.error && <Loading />) : !rows.length ? <Empty title="No matching accounts" /> : (
            <Table>
              <tbody>
                {GL_TYPES.map((type) => {
                  const group = rows.filter((a) => a.type === type);
                  if (!group.length) return null;
                  const total = group.reduce((s, a) => s + a.balance, 0);
                  return [
                    <tr key={type} className="bg-surface-2">
                      <td className="px-4 py-2 text-[12px] font-semibold uppercase tracking-wide text-muted" colSpan={2}>{GL_TYPE_LABEL[type]}</td>
                      <td className="px-4 py-2 text-right text-[13px] font-semibold"><Amt cents={total} /></td>
                      <td className="w-10 px-2" />
                    </tr>,
                    ...group.map((a) => (
                      <tr key={a.id} onClick={() => select(a.id)} className={cx('cursor-pointer hover:bg-surface-2', a.id === selected && 'bg-brand-500/10 hover:bg-brand-500/10', !!a.archived && 'opacity-60')}>
                        <Td className="w-16 py-2 text-muted tabular">{a.code}</Td>
                        <Td className="py-2">
                          <span className="font-medium">{a.name}</span>
                          {a.archived ? <span className="ml-2"><Badge>Archived</Badge></span> : null}
                        </Td>
                        <Td right className="py-2"><Amt cents={a.balance} /></Td>
                        <Td className="w-10 px-2 py-2">
                          <button
                            onClick={(e) => { e.stopPropagation(); setEditing(a); }}
                            className="rounded px-1.5 py-0.5 text-[13px] text-muted hover:bg-surface hover:text-ink"
                            aria-label={`Edit ${a.name}`}
                          >Edit</button>
                        </Td>
                      </tr>
                    )),
                  ];
                })}
              </tbody>
            </Table>
          )}
        </Card>
        {selected && (
          <div className="min-w-0">
            <div className="mb-2 flex justify-end xl:hidden"><Button size="sm" variant="ghost" onClick={() => select(null)}>Close detail</Button></div>
            <LedgerView key={selected} id={selected} accounts={coa.data} />
          </div>
        )}
      </div>

      <AddAccountModal open={adding} onClose={() => setAdding(false)} onDone={(id) => { setAdding(false); coa.reload(); select(id); }} />
      {editing && <EditAccountModal key={editing.id} account={editing} onClose={() => setEditing(null)} onDone={() => { setEditing(null); coa.reload(); }} />}
    </>
  );
}

export default function LedgerPage() {
  return (
    <Suspense fallback={<Loading />}>
      <LedgerInner />
    </Suspense>
  );
}
