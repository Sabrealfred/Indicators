'use client';

import { useEffect, useState } from 'react';
import { post, useApi } from '@/lib/api';
import { can, useSession } from '@/lib/session';
import { date, money, todayIso } from '@/lib/format';
import type { Account, GlAccount, Reimbursement } from '@/lib/types';
import {
  Button, Card, Empty, ErrorNote, Field, Input, Loading, Modal, Money, MoneyInput, PageHeader, Select, Stat, StatusBadge, Table, Tabs, Td, Textarea, Th, Tr, useAction,
} from '@/components/ui';

type Tab = 'review' | 'all';

export default function ReimbursementsPage() {
  const { user } = useSession();
  const isAdmin = can.approve(user.role);
  const list = useApi<Reimbursement[]>('/reimbursements');
  const [tab, setTab] = useState<Tab>(isAdmin ? 'review' : 'all');
  const [reqOpen, setReqOpen] = useState(false);
  const [approving, setApproving] = useState<Reimbursement | null>(null);
  const act = useAction();

  const all = list.data ?? [];
  const toReview = all.filter((r) => r.status === 'submitted');
  const rows = tab === 'review' ? toReview : all;
  const mine = all.filter((r) => r.user_id === user.id);
  const paidMine = mine.filter((r) => r.status === 'paid' || r.status === 'approved');

  const reject = (r: Reimbursement) => act.run(async () => {
    if (!confirm(`Reject ${r.user_name}'s ${money(r.amount)} reimbursement for ${r.merchant}?`)) return;
    await post(`/reimbursements/${r.id}/reject`);
    list.reload();
  });

  return (
    <>
      <PageHeader
        title="Reimbursements"
        subtitle={isAdmin ? 'Review out-of-pocket expenses and pay your team back by ACH.' : 'Get paid back for business expenses you covered personally.'}
        actions={<Button variant="primary" onClick={() => setReqOpen(true)}>Request reimbursement</Button>}
      />

      <div className="mb-6 grid gap-4 sm:grid-cols-3">
        {isAdmin ? (
          <>
            <Stat label="To review" value={money(toReview.reduce((s, r) => s + r.amount, 0))} hint={`${toReview.length} request${toReview.length === 1 ? '' : 's'}`} />
            <Stat label="Paid out" value={money(all.filter((r) => r.status === 'paid').reduce((s, r) => s + r.amount, 0))} hint="All time" />
            <Stat label="Your requests" value={mine.length} hint={`${mine.filter((r) => r.status === 'submitted').length} awaiting review`} />
          </>
        ) : (
          <>
            <Stat label="Awaiting review" value={money(mine.filter((r) => r.status === 'submitted').reduce((s, r) => s + r.amount, 0))} hint={`${mine.filter((r) => r.status === 'submitted').length} request(s)`} />
            <Stat label="Reimbursed" value={money(paidMine.reduce((s, r) => s + r.amount, 0))} tone="pos" />
            <Stat label="Rejected" value={mine.filter((r) => r.status === 'rejected').length} />
          </>
        )}
      </div>

      {isAdmin && (
        <Tabs<Tab>
          value={tab}
          onChange={setTab}
          tabs={[
            { value: 'review', label: <span>To review <span className="ml-1 text-[12px] text-muted">{toReview.length}</span></span> },
            { value: 'all', label: <span>All <span className="ml-1 text-[12px] text-muted">{all.length}</span></span> },
          ]}
        />
      )}

      <ErrorNote error={list.error ?? act.error} />
      <Card padded={false}>
        {list.loading && !list.data ? <Loading /> : !rows.length ? (
          <Empty
            title={tab === 'review' ? 'Nothing to review' : 'No reimbursements yet'}
            hint={tab === 'review' ? 'New requests from your team will show up here.' : 'Paid for something with a personal card? Request a reimbursement.'}
            action={tab !== 'review' && <Button onClick={() => setReqOpen(true)}>Request reimbursement</Button>}
          />
        ) : (
          <Table>
            <thead>
              <tr>
                <Th>Date</Th>
                {isAdmin && <Th className="hidden sm:table-cell">Employee</Th>}
                <Th>Merchant</Th>
                <Th right>Amount</Th>
                <Th className="hidden sm:table-cell">Status</Th>
                {isAdmin && <Th right><span className="sr-only">Actions</span></Th>}
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <Tr key={r.id}>
                  <Td className="whitespace-nowrap text-muted">{date(r.spent_on)}</Td>
                  {isAdmin && <Td className="hidden sm:table-cell">{r.user_name}</Td>}
                  <Td>
                    <div className="font-medium">{r.merchant}</div>
                    <div className="text-[12px] text-muted">
                      {isAdmin && <span className="sm:hidden">{r.user_name} · </span>}
                      {r.description}
                      {r.receipt_name && <span title={r.receipt_name}>{r.description ? ' · ' : ''}📎 {r.receipt_name}</span>}
                    </div>
                    <div className="mt-1 sm:hidden"><StatusBadge status={r.status} /></div>
                  </Td>
                  <Td right><Money cents={r.amount} className="font-medium" /></Td>
                  <Td className="hidden sm:table-cell"><StatusBadge status={r.status} /></Td>
                  {isAdmin && (
                    <Td right>
                      {r.status === 'submitted' && (
                        <div className="flex justify-end gap-1.5">
                          <Button size="sm" variant="primary" disabled={act.busy} onClick={() => setApproving(r)}>Approve</Button>
                          <Button size="sm" variant="ghost" disabled={act.busy} onClick={() => reject(r)}>Reject</Button>
                        </div>
                      )}
                    </Td>
                  )}
                </Tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>

      <RequestModal open={reqOpen} onClose={() => setReqOpen(false)} showCategory={can.books(user.role)} onCreated={() => { setReqOpen(false); setTab('all'); list.reload(); }} />
      {isAdmin && <ApproveModal item={approving} onClose={() => setApproving(null)} onDone={() => { setApproving(null); list.reload(); }} />}
    </>
  );
}

function RequestModal({ open, onClose, onCreated, showCategory }: { open: boolean; onClose: () => void; onCreated: () => void; showCategory: boolean }) {
  // The chart of accounts is admin/bookkeeper-only; employees submit without a category and the reviewer books it.
  const gl = useApi<GlAccount[]>(open && showCategory ? '/books/accounts' : null);
  const { busy, error, run, setError } = useAction();
  const [merchant, setMerchant] = useState('');
  const [amount, setAmount] = useState<number | null>(null);
  const [spentOn, setSpentOn] = useState(todayIso());
  const [glId, setGlId] = useState('');
  const [description, setDescription] = useState('');
  const [receipt, setReceipt] = useState('');
  const [k, setK] = useState(0);

  useEffect(() => {
    if (!open) return;
    setMerchant(''); setAmount(null); setSpentOn(todayIso()); setGlId(''); setDescription(''); setReceipt(''); setError(null); setK((x) => x + 1);
  }, [open, setError]);

  const expenses = (gl.data ?? []).filter((g) => g.type === 'expense' && !g.archived);

  const submit = () => run(async () => {
    if (!merchant.trim()) throw new Error('Enter the merchant');
    if (!amount) throw new Error('Enter the amount');
    if (spentOn > todayIso()) throw new Error('Date spent cannot be in the future');
    await post('/reimbursements', {
      merchant: merchant.trim(), amount, spent_on: spentOn, description: description.trim() || null, receipt_name: receipt || null,
      ...(showCategory && glId ? { gl_account_id: glId } : {}),
    });
    onCreated();
  });

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Request reimbursement"
      footer={<><Button onClick={onClose}>Cancel</Button><Button variant="primary" disabled={busy} onClick={submit}>{busy ? 'Submitting…' : 'Submit request'}</Button></>}
    >
      <Field label="Merchant"><Input autoFocus value={merchant} onChange={(e) => setMerchant(e.target.value)} placeholder="e.g. Blue Bottle Coffee" /></Field>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Amount"><MoneyInput key={k} value={amount} onChange={setAmount} /></Field>
        <Field label="Date spent"><Input type="date" max={todayIso()} value={spentOn} onChange={(e) => setSpentOn(e.target.value)} /></Field>
      </div>
      {showCategory && (
        <Field label="Category">
          <Select value={glId} onChange={(e) => setGlId(e.target.value)}>
            <option value="">Uncategorized</option>
            {expenses.map((g) => <option key={g.id} value={g.id}>{g.code} · {g.name}</option>)}
          </Select>
        </Field>
      )}
      <Field label="Description"><Textarea rows={2} value={description} onChange={(e) => setDescription(e.target.value)} placeholder="Business purpose" /></Field>
      <Field label="Receipt" hint={receipt ? `Attached: ${receipt}` : 'Photo or PDF of the receipt'}>
        <input
          key={k}
          type="file"
          accept=".pdf,image/*"
          onChange={(e) => setReceipt(e.target.files?.[0]?.name ?? '')}
          className="block w-full text-[13px] text-muted file:mr-3 file:rounded-md file:border file:border-line file:bg-surface file:px-3 file:py-1.5 file:text-ink hover:file:bg-surface-2"
        />
      </Field>
      <ErrorNote error={error} />
    </Modal>
  );
}

function ApproveModal({ item, onClose, onDone }: { item: Reimbursement | null; onClose: () => void; onDone: () => void }) {
  const open = !!item;
  const accounts = useApi<Account[]>(open ? '/accounts' : null);
  const { busy, error, run, setError } = useAction();
  const [from, setFrom] = useState('');
  useEffect(() => { if (open) setError(null); }, [open, setError]);
  const sources = (accounts.data ?? []).filter((a) => a.type === 'checking' && a.status === 'open');

  const approve = () => run(async () => {
    if (!item) return;
    await post(`/reimbursements/${item.id}/approve`, from ? { account_id: from } : {});
    onDone();
  });

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Approve reimbursement"
      footer={<><Button onClick={onClose}>Cancel</Button><Button variant="primary" disabled={busy} onClick={approve}>{busy ? 'Approving…' : `Approve & pay ${money(item?.amount)}`}</Button></>}
    >
      {item && (
        <div className="flex items-center justify-between rounded-lg bg-surface-2 px-4 py-3">
          <div>
            <div className="font-medium">{item.merchant}</div>
            <div className="text-[12px] text-muted">{item.user_name} · {date(item.spent_on)}{item.receipt_name ? ` · 📎 ${item.receipt_name}` : ' · No receipt'}</div>
          </div>
          <Money cents={item.amount} className="text-lg font-semibold" />
        </div>
      )}
      {item?.description && <p className="text-muted">{item.description}</p>}
      <Field label="Pay from" hint="The employee is paid by ACH.">
        <Select value={from} onChange={(e) => setFrom(e.target.value)}>
          <option value="">Default account</option>
          {sources.map((a) => <option key={a.id} value={a.id}>{a.name} · {money(a.balance.available)} available</option>)}
        </Select>
      </Field>
      <ErrorNote error={error ?? accounts.error} />
    </Modal>
  );
}
