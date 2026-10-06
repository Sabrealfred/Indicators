'use client';

import { useState } from 'react';
import { post, useApi } from '@/lib/api';
import { can, useSession } from '@/lib/session';
import { addDaysIso, date, money } from '@/lib/format';
import { Badge, Button, Card, Empty, ErrorNote, Field, Input, Loading, PageHeader, Table, Td, Th, useAction } from '@/components/ui';
import { Amt, useToday, type ReconRow } from '../_shared';

function ReconcileRow({ r, today, onDone }: { r: ReconRow; today: string; onDone: (msg: string) => void }) {
  const [through, setThrough] = useState('');
  const a = useAction();
  const value = through || today;
  const submit = async () => {
    if (!confirm(`Mark all posted ${r.name} transactions through ${date(value)} as reconciled?`)) return;
    const res = await a.run(() => post<{ reconciled: number }>(`/books/reconciliation/${r.account_id}`, { through: value }));
    if (res) onDone(`${r.name}: ${res.reconciled} transaction${res.reconciled === 1 ? '' : 's'} marked reconciled through ${date(value)}.`);
  };
  return (
    <tr className="hover:bg-surface-2">
      <Td>
        <div className="font-medium">{r.name}</div>
        <div className="text-[12px] capitalize text-muted">{r.type}</div>
      </Td>
      <Td right><Amt cents={r.bank_balance} /></Td>
      <Td right><Amt cents={r.book_balance} /></Td>
      <Td right>
        {r.difference === 0 ? <span className="font-medium text-pos">✓ $0.00</span> : <span className="font-semibold text-neg">{money(r.difference)}</span>}
      </Td>
      <Td right>{r.unreconciled > 0 ? <Badge tone="yellow">{r.unreconciled}</Badge> : <Badge tone="green">All</Badge>}</Td>
      <Td>
        <div className="flex items-center justify-end gap-2">
          <Input type="date" value={value} max={today} onChange={(e) => setThrough(e.target.value)} className="w-40" aria-label="Reconcile through" />
          <Button size="sm" onClick={submit} disabled={a.busy || r.unreconciled === 0}>{a.busy ? 'Saving…' : 'Reconcile'}</Button>
        </div>
        {a.error && <div className="mt-1 text-right text-[12px] text-neg">{a.error}</div>}
      </Td>
    </tr>
  );
}

function CloseBooks() {
  const { user, organization, reload } = useSession();
  const today = useToday();
  const isAdmin = can.admin(user.role);
  const locked = organization.books_locked_through;
  const lastMonthEnd = addDaysIso(`${today.slice(0, 7)}-01`, -1);
  const [through, setThrough] = useState('');
  const a = useAction();
  const value = through || lastMonthEnd;

  const close = async (t: string | null) => {
    const msg = t ? `Close the books through ${date(t)}? Entries dated on or before that day will be locked.` : `Reopen the books? All periods will become editable again.`;
    if (!confirm(msg)) return;
    const r = await a.run(() => post('/books/close', { through: t }));
    if (r) { setThrough(''); reload(); }
  };

  return (
    <Card title="Period close">
      <p className="mb-4 text-[13px] text-muted">
        Closing locks every journal entry dated on or before the close date — no new postings, re-categorizations or deletions can land in a closed period.
      </p>
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <span className="text-muted">Books locked through</span>
        {locked ? <Badge tone="blue">{date(locked)}</Badge> : <Badge tone="green">Open — nothing locked</Badge>}
      </div>
      {isAdmin ? (
        <div className="flex flex-wrap items-end gap-2">
          <Field label="Close through">
            <Input type="date" value={value} max={addDaysIso(today, -1)} onChange={(e) => setThrough(e.target.value)} className="w-44" />
          </Field>
          <Button variant="primary" onClick={() => close(value)} disabled={a.busy || !value || value >= today}>Close books</Button>
          {locked && <Button variant="danger" onClick={() => close(null)} disabled={a.busy}>Reopen all periods</Button>}
        </div>
      ) : (
        <p className="text-[13px] text-muted">Only admins can close or reopen periods.</p>
      )}
      <div className="mt-3"><ErrorNote error={a.error} /></div>
    </Card>
  );
}

export default function ReconcilePage() {
  const r = useApi<ReconRow[]>('/books/reconciliation');
  const today = useToday();
  const [msg, setMsg] = useState<string | null>(null);
  const off = r.data?.filter((x) => x.difference !== 0).length ?? 0;

  return (
    <>
      <PageHeader title="Reconciliation" subtitle="Compare bank balances against the books and lock completed periods." />
      {msg && <div className="mb-4 rounded-lg border border-emerald-500/30 bg-emerald-500/5 px-3 py-2 text-[13px] text-pos">{msg}</div>}
      <ErrorNote error={r.error} />
      <Card
        title="Bank vs. books"
        className="mb-6"
        padded={false}
        actions={r.data && (off ? <Badge tone="red">{off} account{off > 1 ? 's' : ''} off</Badge> : <Badge tone="green">All accounts tie out</Badge>)}
      >
        {!r.data ? (!r.error && <Loading />) : !r.data.length ? <Empty title="No open accounts" /> : (
          <Table>
            <thead>
              <tr><Th>Account</Th><Th right>Bank balance</Th><Th right>Book balance</Th><Th right>Difference</Th><Th right>Unreconciled</Th><Th right>Reconcile through</Th></tr>
            </thead>
            <tbody>
              {r.data.map((x) => <ReconcileRow key={x.account_id} r={x} today={today} onDone={(m) => { setMsg(m); r.reload(); }} />)}
            </tbody>
          </Table>
        )}
        <p className="border-t border-line px-5 py-3 text-[12px] text-muted">Credit card balances are shown as negative (amount owed). Reconciling marks posted transactions on or before the date as matched to the statement.</p>
      </Card>
      <CloseBooks />
    </>
  );
}
