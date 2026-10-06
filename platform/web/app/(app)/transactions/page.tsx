'use client';

import { Suspense, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { download, useApi } from '@/lib/api';
import type { Account, Transaction } from '@/lib/types';
import { Button, Card, ErrorNote, Input, PageHeader, Select } from '@/components/ui';
import { TxDrawer, TxTable } from './_tx';

const LIMIT = 50;

export default function TransactionsPage() {
  return (
    <Suspense fallback={null}>
      <Transactions />
    </Suspense>
  );
}

function Transactions() {
  const sp = useSearchParams();
  const accounts = useApi<Account[]>('/accounts');
  const [accountId, setAccountId] = useState(sp.get('account_id') ?? '');
  const [status, setStatus] = useState(sp.get('status') ?? '');
  const [direction, setDirection] = useState(sp.get('direction') ?? '');
  const [searchText, setSearchText] = useState(sp.get('search') ?? '');
  const [search, setSearch] = useState(searchText);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [uncategorized, setUncategorized] = useState(sp.get('uncategorized') === 'true');
  const [offset, setOffset] = useState(0);
  const [open, setOpen] = useState<Transaction | null>(null);

  // Debounce the search box.
  useEffect(() => {
    const t = setTimeout(() => setSearch(searchText.trim()), 300);
    return () => clearTimeout(t);
  }, [searchText]);

  const filterQs = useMemo(() => {
    const q = new URLSearchParams();
    if (accountId) q.set('account_id', accountId);
    if (status) q.set('status', status);
    if (direction) q.set('direction', direction);
    if (search) q.set('search', search);
    if (from) q.set('from', from);
    if (to) q.set('to', to);
    if (uncategorized) q.set('uncategorized', 'true');
    return q.toString();
  }, [accountId, status, direction, search, from, to, uncategorized]);

  useEffect(() => setOffset(0), [filterQs]);

  const tx = useApi<{ total: number; rows: Transaction[] }>(`/transactions?${filterQs}${filterQs ? '&' : ''}limit=${LIMIT}&offset=${offset}`);
  const total = tx.data?.total ?? 0;
  const filtersActive = !!filterQs;

  const reset = () => {
    setAccountId('');
    setStatus('');
    setDirection('');
    setSearchText('');
    setSearch('');
    setFrom('');
    setTo('');
    setUncategorized(false);
  };

  return (
    <>
      <PageHeader
        title="Transactions"
        subtitle={tx.data ? `${total.toLocaleString()} transaction${total === 1 ? '' : 's'}` : undefined}
        actions={
          <Button onClick={() => download(`/transactions?format=csv&limit=5000${filterQs ? '&' + filterQs : ''}`, 'transactions.csv')}>
            Export CSV
          </Button>
        }
      />

      <Card className="mb-4">
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Input type="search" placeholder="Search counterparty, description, note" value={searchText} onChange={(e) => setSearchText(e.target.value)} className="lg:col-span-2" />
          <Select value={accountId} onChange={(e) => setAccountId(e.target.value)} aria-label="Account">
            <option value="">All accounts</option>
            {accounts.data?.map((a) => (
              <option key={a.id} value={a.id}>{a.name} ••{a.account_number.slice(-4)}</option>
            ))}
          </Select>
          <div className="grid grid-cols-2 gap-3">
            <Select value={status} onChange={(e) => setStatus(e.target.value)} aria-label="Status">
              <option value="">Any status</option>
              <option value="pending">Pending</option>
              <option value="posted">Posted</option>
              <option value="declined">Declined</option>
              <option value="failed">Failed</option>
              <option value="reversed">Reversed</option>
            </Select>
            <Select value={direction} onChange={(e) => setDirection(e.target.value)} aria-label="Direction">
              <option value="">In &amp; out</option>
              <option value="in">Money in</option>
              <option value="out">Money out</option>
            </Select>
          </div>
          <label className="flex items-center gap-2 text-[13px]">
            <span className="w-10 shrink-0 text-muted">From</span>
            <Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          </label>
          <label className="flex items-center gap-2 text-[13px]">
            <span className="w-10 shrink-0 text-muted">To</span>
            <Input type="date" value={to} onChange={(e) => setTo(e.target.value)} />
          </label>
          <label className="flex items-center gap-2 text-[13px]">
            <input type="checkbox" className="h-4 w-4 accent-brand-600" checked={uncategorized} onChange={(e) => setUncategorized(e.target.checked)} />
            Uncategorized only
          </label>
          <div className="flex items-center justify-end">
            {filtersActive && <Button variant="ghost" size="sm" onClick={reset}>Clear filters</Button>}
          </div>
        </div>
      </Card>

      <ErrorNote error={tx.error} />

      <Card padded={false}>
        <TxTable rows={tx.loading && !tx.data ? undefined : tx.data?.rows} onOpen={setOpen} />
        {total > LIMIT && (
          <div className="flex items-center justify-between gap-3 px-4 py-3 text-[13px] text-muted">
            <span className="tabular">
              {offset + 1}–{Math.min(offset + LIMIT, total)} of {total.toLocaleString()}
            </span>
            <div className="flex gap-2">
              <Button size="sm" disabled={offset === 0 || tx.loading} onClick={() => setOffset(Math.max(0, offset - LIMIT))}>← Previous</Button>
              <Button size="sm" disabled={offset + LIMIT >= total || tx.loading} onClick={() => setOffset(offset + LIMIT)}>Next →</Button>
            </div>
          </div>
        )}
      </Card>

      <TxDrawer tx={open} onClose={() => setOpen(null)} onChanged={tx.reload} />
    </>
  );
}
