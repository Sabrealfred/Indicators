'use client';

import Link from 'next/link';
import { useEffect, useState, type ReactNode } from 'react';
import { patch, useApi } from '@/lib/api';
import { can, useSession } from '@/lib/session';
import { dateTime, shortDate, titleCase } from '@/lib/format';
import type { GlAccount, Transaction } from '@/lib/types';
import { Badge, Button, Drawer, Empty, ErrorNote, Field, KeyValue, Loading, Money, Select, StatusBadge, Table, Td, Textarea, Th, Tr, useAction } from '@/components/ui';

/** Transaction feed table shared by /transactions and /accounts/[id]. */
export function TxTable({ rows, onOpen, showAccount = true, emptyHint }: { rows?: Transaction[]; onOpen: (t: Transaction) => void; showAccount?: boolean; emptyHint?: string }) {
  if (!rows) return <Loading />;
  if (!rows.length) return <Empty title="No transactions" hint={emptyHint ?? 'Try adjusting your filters.'} />;
  return (
    <Table>
      <thead>
        <tr>
          <Th className="w-24">Date</Th>
          <Th>To / from</Th>
          {showAccount && <Th>Account</Th>}
          <Th>Category</Th>
          <Th />
          <Th right>Amount</Th>
        </tr>
      </thead>
      <tbody>
        {rows.map((t) => (
          <Tr key={t.id} onClick={() => onOpen(t)}>
            <Td className="whitespace-nowrap text-muted">{shortDate(t.created_at)}</Td>
            <Td className="min-w-48">
              <div className="font-medium">{t.counterparty_name}</div>
              <div className="max-w-72 truncate text-[12px] text-muted">
                {t.description}
                {t.note ? ` · ${t.note}` : ''}
              </div>
            </Td>
            {showAccount && (
              <Td className="min-w-36">
                <div>{t.account_name}</div>
                {t.card_last4 && <div className="text-[12px] text-muted">••{t.card_last4}{t.cardholder ? ` · ${t.cardholder}` : ''}</div>}
              </Td>
            )}
            <Td className="min-w-32">
              {t.category_name ? <span className="text-[13px]">{t.category_name}</span> : <span className="text-[13px] text-muted">—</span>}
            </Td>
            <Td className="whitespace-nowrap">
              {t.status !== 'posted' && <StatusBadge status={t.status} />}
              {t.receipt_name && <span className="ml-1 text-[12px] text-muted" title={t.receipt_name}>📎</span>}
            </Td>
            <Td right className="whitespace-nowrap font-medium">
              <Money cents={t.amount} signed className={t.status === 'declined' || t.status === 'failed' || t.status === 'reversed' ? 'line-through opacity-60' : undefined} />
            </Td>
          </Tr>
        ))}
      </tbody>
    </Table>
  );
}

/** Category <select> options grouped by GL account type. */
export function GlOptions({ accounts, types }: { accounts: GlAccount[]; types?: GlAccount['type'][] }) {
  const order: GlAccount['type'][] = types ?? ['expense', 'revenue', 'asset', 'liability', 'equity'];
  return (
    <>
      {order.map((type) => {
        const list = accounts.filter((a) => a.type === type && !a.archived);
        if (!list.length) return null;
        return (
          <optgroup key={type} label={titleCase(type)}>
            {list.map((a) => (
              <option key={a.id} value={a.id}>{a.code} · {a.name}</option>
            ))}
          </optgroup>
        );
      })}
    </>
  );
}

/** Detail drawer for a single transaction with categorization, notes, receipt and reconciliation. */
export function TxDrawer({ tx, onClose, onChanged }: { tx: Transaction | null; onClose: () => void; onChanged: () => void }) {
  const { user } = useSession();
  const books = can.books(user.role);
  const gl = useApi<GlAccount[]>(tx && books ? '/books/accounts' : null);
  const [cur, setCur] = useState<Transaction | null>(tx);
  const [note, setNote] = useState('');
  const act = useAction();
  const [saved, setSaved] = useState<string | null>(null);

  useEffect(() => {
    setCur(tx);
    setNote(tx?.note ?? '');
    setSaved(null);
    act.setError(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tx?.id]);

  if (!tx || !cur) return null;

  const update = async (body: Record<string, unknown>, msg: string) => {
    setSaved(null);
    const r = await act.run(() => patch<Transaction>(`/transactions/${cur.id}`, body));
    if (r) {
      setCur(r);
      setSaved(msg);
      onChanged();
    }
  };

  return (
    <Drawer open onClose={onClose} title="Transaction">
      <div className="mb-5 text-center">
        <div className="text-[13px] text-muted">{cur.counterparty_name}</div>
        <div className="mt-1 text-3xl font-semibold tracking-tight"><Money cents={cur.amount} signed /></div>
        <div className="mt-2 flex justify-center gap-2">
          <StatusBadge status={cur.status} />
          {cur.reconciled ? <Badge tone="green">Reconciled</Badge> : null}
        </div>
      </div>

      {cur.status === 'declined' && cur.decline_reason && (
        <div className="mb-4 rounded-lg border border-red-500/30 bg-red-500/5 px-3 py-2 text-[13px] text-neg">
          Declined: {cur.decline_reason}
        </div>
      )}

      <KeyValue
        rows={[
          ['Date', dateTime(cur.created_at)],
          ['Posted', cur.posted_at ? dateTime(cur.posted_at) : <span className="text-muted">Not yet</span>],
          ['Description', <span key="d" className="break-words">{cur.description || '—'}</span>],
          ['Type', titleCase(cur.kind)],
          ['Account', <Link key="a" href={`/accounts/${cur.account_id}`} className="text-brand-600 hover:underline">{cur.account_name}</Link>],
          ...(cur.card_last4 ? ([['Card', `••${cur.card_last4}${cur.cardholder ? ` · ${cur.cardholder}` : ''}`]] as [string, string][]) : []),
          ...(cur.merchant_category ? ([['Merchant category', titleCase(cur.merchant_category)]] as [string, string][]) : []),
          ['Category', cur.category_name ? `${cur.category_code} · ${cur.category_name}` : '—'],
          ...(cur.payment_id
            ? ([['Payment', <Link key="p" href={`/payments?payment=${cur.payment_id}`} className="text-brand-600 hover:underline">View payment →</Link>]] as [string, ReactNode][])
            : []),
        ]}
      />

      <div className="mt-6 space-y-4">
        {books && (
          <Field label="Category" hint="Re-books the transaction to this ledger account.">
            {gl.data ? (
              <Select value={cur.gl_account_id ?? ''} disabled={act.busy} onChange={(e) => e.target.value && update({ gl_account_id: e.target.value }, 'Category updated')}>
                <option value="">Select a category…</option>
                <GlOptions accounts={gl.data} />
              </Select>
            ) : (
              <div className="text-[13px] text-muted">{gl.error ?? 'Loading categories…'}</div>
            )}
          </Field>
        )}

        <Field label="Note">
          <Textarea value={note} onChange={(e) => setNote(e.target.value)} placeholder="Add context for your team or accountant" />
        </Field>
        <div className="flex justify-end">
          <Button size="sm" disabled={act.busy || note === (cur.note ?? '')} onClick={() => update({ note: note.trim() || null }, 'Note saved')}>Save note</Button>
        </div>

        <Field label="Receipt">
          {cur.receipt_name ? (
            <div className="flex items-center justify-between gap-2 rounded-lg border border-line px-3 py-2">
              <span className="truncate text-[13px]">📎 {cur.receipt_name}</span>
              <Button size="sm" variant="ghost" disabled={act.busy} onClick={() => update({ receipt_name: null }, 'Receipt removed')}>Remove</Button>
            </div>
          ) : (
            <input
              type="file"
              accept="image/*,application/pdf"
              disabled={act.busy}
              className="block w-full text-[13px] file:mr-3 file:rounded-lg file:border file:border-line file:bg-surface file:px-3 file:py-1.5 file:font-medium hover:file:bg-surface-2"
              onChange={(e) => {
                const f = e.target.files?.[0];
                if (f) update({ receipt_name: f.name }, 'Receipt attached');
                e.target.value = '';
              }}
            />
          )}
        </Field>

        {books && (
          <label className="flex items-center justify-between gap-3 rounded-lg border border-line px-3 py-2.5">
            <span>
              <span className="block font-medium">Reconciled</span>
              <span className="block text-[12px] text-muted">Matched to the books for this period</span>
            </span>
            <input type="checkbox" className="h-4 w-4 accent-brand-600" checked={!!cur.reconciled} disabled={act.busy} onChange={(e) => update({ reconciled: e.target.checked }, e.target.checked ? 'Marked reconciled' : 'Marked unreconciled')} />
          </label>
        )}

        <ErrorNote error={act.error} />
        {saved && !act.error && <div className="text-[13px] text-pos">{saved}</div>}
      </div>
    </Drawer>
  );
}
