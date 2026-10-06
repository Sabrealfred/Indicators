'use client';

import { useState } from 'react';
import { del, post, useApi } from '@/lib/api';
import { date, money } from '@/lib/format';
import type { GlAccount } from '@/lib/types';
import { Button, Card, Empty, ErrorNote, Field, Input, Loading, Modal, MoneyInput, PageHeader, Select, Table, Td, Th, cx, useAction } from '@/components/ui';
import { GlSelect, SourceBadge, acct, useToday, type JournalEntry } from '../_shared';

const SOURCES = ['transaction', 'transfer', 'bill', 'invoice', 'manual'] as const;

interface DraftLine { key: number; gl_account_id: string; debit: number | null; credit: number | null; description: string }
let seq = 0;
const blank = (): DraftLine => ({ key: ++seq, gl_account_id: '', debit: null, credit: null, description: '' });

function NewEntryModal({ open, onClose, onDone, accounts }: { open: boolean; onClose: () => void; onDone: () => void; accounts: GlAccount[] }) {
  const today = useToday();
  const [d, setD] = useState('');
  const [memo, setMemo] = useState('');
  const [lines, setLines] = useState<DraftLine[]>(() => [blank(), blank()]);
  const a = useAction();

  const dr = lines.reduce((s, l) => s + (l.debit ?? 0), 0);
  const cr = lines.reduce((s, l) => s + (l.credit ?? 0), 0);
  const used = lines.filter((l) => l.gl_account_id && ((l.debit ?? 0) > 0 || (l.credit ?? 0) > 0));
  const bothSides = lines.some((l) => (l.debit ?? 0) > 0 && (l.credit ?? 0) > 0);
  const missingAccount = lines.some((l) => !l.gl_account_id && ((l.debit ?? 0) > 0 || (l.credit ?? 0) > 0));
  const balanced = dr === cr && dr > 0;
  const canSubmit = balanced && used.length >= 2 && !bothSides && !missingAccount && memo.trim() && !a.busy;

  const set = (key: number, p: Partial<DraftLine>) => setLines((ls) => ls.map((l) => (l.key === key ? { ...l, ...p } : l)));
  const reset = () => { setMemo(''); setD(''); setLines([blank(), blank()]); };

  const submit = async () => {
    const r = await a.run(() => post('/books/journal', {
      date: d || today, memo: memo.trim(),
      lines: used.map((l) => ({ gl_account_id: l.gl_account_id, debit: l.debit ?? 0, credit: l.credit ?? 0, description: l.description.trim() || undefined })),
    }));
    if (r) { reset(); onDone(); }
  };

  return (
    <Modal open={open} onClose={onClose} title="New journal entry" wide footer={
      <>
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        <Button variant="primary" disabled={!canSubmit} onClick={submit}>{a.busy ? 'Posting…' : 'Post entry'}</Button>
      </>
    }>
      <div className="grid gap-3 sm:grid-cols-[180px_1fr]">
        <Field label="Date"><Input type="date" value={d || today} onChange={(e) => setD(e.target.value)} /></Field>
        <Field label="Memo"><Input value={memo} onChange={(e) => setMemo(e.target.value)} placeholder="e.g. Accrue September contractor invoices" autoFocus /></Field>
      </div>

      <div className="overflow-x-auto">
        <div className="min-w-[620px] space-y-2">
          <div className="grid grid-cols-[minmax(0,2fr)_120px_120px_minmax(0,1.4fr)_32px] gap-2 text-[12px] font-medium uppercase tracking-wide text-muted">
            <span>Account</span><span className="text-right">Debit</span><span className="text-right">Credit</span><span>Description</span><span />
          </div>
          {lines.map((l) => (
            <div key={l.key} className="grid grid-cols-[minmax(0,2fr)_120px_120px_minmax(0,1.4fr)_32px] items-center gap-2">
              <GlSelect accounts={accounts} value={l.gl_account_id} onChange={(id) => set(l.key, { gl_account_id: id })} />
              <MoneyInput value={l.debit} onChange={(c) => set(l.key, { debit: c })} />
              <MoneyInput value={l.credit} onChange={(c) => set(l.key, { credit: c })} />
              <Input value={l.description} onChange={(e) => set(l.key, { description: e.target.value })} placeholder="Optional" />
              <button
                onClick={() => setLines((ls) => ls.filter((x) => x.key !== l.key))}
                disabled={lines.length <= 2}
                className="h-8 rounded text-muted hover:bg-surface-2 hover:text-ink disabled:opacity-30"
                aria-label="Remove line"
              >✕</button>
            </div>
          ))}
          <div className="grid grid-cols-[minmax(0,2fr)_120px_120px_minmax(0,1.4fr)_32px] items-center gap-2 border-t border-line pt-2">
            <Button size="sm" variant="ghost" className="justify-self-start" onClick={() => setLines((ls) => [...ls, blank()])}>+ Add line</Button>
            <span className="pr-3 text-right font-semibold tabular">{money(dr)}</span>
            <span className="pr-3 text-right font-semibold tabular">{money(cr)}</span>
            <span className={cx('text-[13px] font-medium', balanced ? 'text-pos' : 'text-neg')}>
              {balanced ? '✓ Balanced' : dr === 0 && cr === 0 ? <span className="text-muted">Enter amounts</span> : `Out of balance by ${money(Math.abs(dr - cr))}`}
            </span>
            <span />
          </div>
        </div>
      </div>
      {bothSides && <p className="text-[12px] text-neg">Each line should have a debit or a credit, not both.</p>}
      {missingAccount && <p className="text-[12px] text-neg">Choose an account for every line with an amount.</p>}
      <ErrorNote error={a.error} />
    </Modal>
  );
}

export default function JournalPage() {
  const [source, setSource] = useState('');
  const [limit, setLimit] = useState(100);
  const j = useApi<JournalEntry[]>(`/books/journal?limit=${limit}${source ? `&source_type=${source}` : ''}`);
  const coa = useApi<GlAccount[]>('/books/accounts');
  const [creating, setCreating] = useState(false);
  const rm = useAction();

  const remove = async (e: JournalEntry) => {
    if (!confirm(`Delete manual journal entry "${e.memo}"? This cannot be undone.`)) return;
    const r = await rm.run(() => del(`/books/journal/${e.id}`));
    if (r) j.reload();
  };

  return (
    <>
      <PageHeader
        title="Journal entries"
        subtitle="Every posting in the general ledger. Bank activity, bills and invoices post automatically."
        actions={<Button variant="primary" onClick={() => setCreating(true)} disabled={!coa.data}>New journal entry</Button>}
      />
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <Field label="Source">
          <Select value={source} onChange={(e) => setSource(e.target.value)} className="w-48">
            <option value="">All sources</option>
            {SOURCES.map((s) => <option key={s} value={s}>{s.charAt(0).toUpperCase() + s.slice(1)}</option>)}
          </Select>
        </Field>
      </div>
      <div className="mb-4 space-y-2"><ErrorNote error={j.error || rm.error || coa.error} /></div>

      <Card padded={false}>
        {!j.data ? (!j.error && <Loading />) : !j.data.length ? <Empty title="No journal entries" hint={source ? 'Try a different source filter.' : undefined} /> : (
          <Table>
            <thead>
              <tr><Th className="w-28">Date</Th><Th>Account / memo</Th><Th right>Debit</Th><Th right>Credit</Th><Th className="w-16" /></tr>
            </thead>
            {j.data.map((e) => (
              <tbody key={e.id} className="border-b-2 border-line">
                <tr className="bg-surface-2/60">
                  <td className="whitespace-nowrap px-4 pb-1 pt-3 align-top text-muted">{date(e.date)}</td>
                  <td className="px-4 pb-1 pt-3" colSpan={3}>
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-medium">{e.memo}</span>
                      <SourceBadge type={e.source_type} />
                    </div>
                  </td>
                  <td className="px-4 pb-1 pt-3 text-right">
                    {e.source_type === 'manual' && (
                      <Button size="sm" variant="danger" onClick={() => remove(e)} disabled={rm.busy}>Delete</Button>
                    )}
                  </td>
                </tr>
                {e.lines.map((l) => (
                  <tr key={l.id}>
                    <td />
                    <td className={cx('py-1 pr-4 text-[13px]', l.amount < 0 ? 'pl-10' : 'pl-4')}>
                      <span className="mr-2 text-muted tabular">{l.code}</span>{l.name}
                      {l.description && <span className="ml-2 text-muted">— {l.description}</span>}
                    </td>
                    <td className="px-4 py-1 text-right text-[13px] tabular">{l.amount > 0 ? acct(l.amount) : ''}</td>
                    <td className="px-4 py-1 text-right text-[13px] tabular">{l.amount < 0 ? acct(-l.amount) : ''}</td>
                    <td />
                  </tr>
                ))}
                <tr><td colSpan={5} className="h-2" /></tr>
              </tbody>
            ))}
          </Table>
        )}
        {j.data && j.data.length >= limit && (
          <div className="border-t border-line p-3 text-center">
            <Button size="sm" onClick={() => setLimit((l) => Math.min(l + 200, 1000))} disabled={limit >= 1000}>Load more</Button>
          </div>
        )}
      </Card>

      {coa.data && (
        <NewEntryModal open={creating} onClose={() => setCreating(false)} onDone={() => { setCreating(false); j.reload(); coa.reload(); }} accounts={coa.data} />
      )}
    </>
  );
}
