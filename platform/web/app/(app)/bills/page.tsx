'use client';

import { useEffect, useMemo, useState } from 'react';
import { post, useApi } from '@/lib/api';
import { can, useSession } from '@/lib/session';
import { RAIL_LABELS, addDaysIso, date, money, todayIso } from '@/lib/format';
import type { Account, Bill, GlAccount, Payment, Recipient } from '@/lib/types';
import {
  Badge, Button, Card, Empty, ErrorNote, Field, Input, Loading, Modal, Money, MoneyInput, PageHeader, Select, Stat, StatusBadge, Table, Tabs, Td, Textarea, Th, Tr, cx, useAction,
} from '@/components/ui';

type Tab = 'inbox' | 'approved' | 'scheduled' | 'paid' | 'all';
const TAB_STATUS: Record<Tab, Bill['status'][] | null> = {
  inbox: ['draft', 'pending_approval'], approved: ['approved'], scheduled: ['scheduled'], paid: ['paid'], all: null,
};
const OPEN: Bill['status'][] = ['draft', 'pending_approval', 'approved', 'scheduled'];
const PAY_RAILS = ['ach', 'same_day_ach', 'wire', 'intl_wire', 'check'] as const;

export default function BillsPage() {
  const { user } = useSession();
  const isAdmin = can.approve(user.role);
  const bills = useApi<Bill[]>('/bills');
  const [tab, setTab] = useState<Tab>('inbox');
  const [addOpen, setAddOpen] = useState(false);
  const [paying, setPaying] = useState<Bill | null>(null);
  const act = useAction();

  const list = bills.data ?? [];
  const today = todayIso();
  const in7 = addDaysIso(today, 7);
  const isOverdue = (b: Bill) => !!b.overdue || (['draft', 'pending_approval', 'approved'].includes(b.status) && b.due_date < today);
  const outstanding = list.filter((b) => OPEN.includes(b.status));
  const due7 = outstanding.filter((b) => b.due_date >= today && b.due_date <= in7);
  const overdue = list.filter(isOverdue);
  const sum = (xs: Bill[]) => xs.reduce((s, b) => s + b.amount, 0);

  const filtered = useMemo(() => {
    const st = TAB_STATUS[tab];
    const rows = st ? list.filter((b) => st.includes(b.status)) : list;
    return [...rows].sort((a, b) => (tab === 'paid' || tab === 'all' ? b.due_date.localeCompare(a.due_date) : a.due_date.localeCompare(b.due_date)));
  }, [list, tab]);
  const count = (t: Tab) => (TAB_STATUS[t] ? list.filter((b) => TAB_STATUS[t]!.includes(b.status)).length : list.length);

  const action = (b: Bill, verb: 'submit' | 'approve' | 'void') => act.run(async () => {
    if (verb === 'void' && !confirm(`Void bill ${b.invoice_number ?? ''} from ${b.vendor_name} for ${money(b.amount)}?`)) return;
    await post(`/bills/${b.id}/${verb}`);
    bills.reload();
  });

  return (
    <>
      <PageHeader
        title="Bill pay"
        subtitle="Collect, approve and pay vendor bills."
        actions={<Button variant="primary" onClick={() => setAddOpen(true)}>Add bill</Button>}
      />

      <div className="mb-6 grid gap-4 sm:grid-cols-3">
        <Stat label="Total outstanding" value={money(sum(outstanding))} hint={`${outstanding.length} open bill${outstanding.length === 1 ? '' : 's'}`} />
        <Stat label="Due in 7 days" value={money(sum(due7))} hint={`${due7.length} bill${due7.length === 1 ? '' : 's'}`} />
        <Stat label="Overdue" value={money(sum(overdue))} tone={overdue.length ? 'neg' : undefined} hint={`${overdue.length} bill${overdue.length === 1 ? '' : 's'}`} />
      </div>

      <Tabs<Tab>
        value={tab}
        onChange={setTab}
        tabs={([['inbox', 'Inbox'], ['approved', 'To pay'], ['scheduled', 'Scheduled'], ['paid', 'Paid'], ['all', 'All']] as [Tab, string][]).map(([v, l]) => ({
          value: v, label: <span>{l} <span className="ml-1 text-[12px] text-muted">{count(v)}</span></span>,
        }))}
      />

      <ErrorNote error={bills.error ?? act.error} />
      <Card padded={false}>
        {bills.loading && !bills.data ? <Loading /> : !filtered.length ? (
          <Empty title={tab === 'inbox' ? 'Inbox zero' : 'No bills here'} hint={tab === 'inbox' ? 'New bills you add will land here for review.' : undefined} action={tab === 'inbox' && <Button onClick={() => setAddOpen(true)}>Add bill</Button>} />
        ) : (
          <Table>
            <thead>
              <tr><Th>Vendor</Th><Th className="hidden sm:table-cell">Invoice #</Th><Th>Due</Th><Th right>Amount</Th><Th className="hidden md:table-cell">Status</Th><Th right><span className="sr-only">Actions</span></Th></tr>
            </thead>
            <tbody>
              {filtered.map((b) => {
                const od = isOverdue(b);
                return (
                  <Tr key={b.id}>
                    <Td>
                      <div className="font-medium">{b.vendor_name}</div>
                      <div className="text-[12px] text-muted">
                        <span className="sm:hidden">{b.invoice_number ? `#${b.invoice_number} · ` : ''}</span>
                        <span className="md:hidden">{b.status.replace(/_/g, ' ')}{b.memo ? ' · ' : ''}</span>
                        {b.memo}
                        {b.attachment_name && <span title={b.attachment_name}> · 📎</span>}
                      </div>
                    </Td>
                    <Td className="hidden text-muted sm:table-cell">{b.invoice_number ?? '—'}</Td>
                    <Td className={cx('whitespace-nowrap', od && 'font-medium text-neg')}>
                      {date(b.due_date)}
                      {od && <div className="text-[11px] font-normal">Overdue</div>}
                    </Td>
                    <Td right><Money cents={b.amount} className="font-medium" /></Td>
                    <Td className="hidden md:table-cell"><StatusBadge status={b.status} /></Td>
                    <Td right>
                      <div className="flex justify-end gap-1.5">
                        {b.status === 'draft' && <Button size="sm" disabled={act.busy} onClick={() => action(b, 'submit')}>Submit</Button>}
                        {isAdmin && (b.status === 'draft' || b.status === 'pending_approval') && <Button size="sm" variant={b.status === 'pending_approval' ? 'primary' : 'secondary'} disabled={act.busy} onClick={() => action(b, 'approve')}>Approve</Button>}
                        {b.status === 'approved' && <Button size="sm" variant="primary" onClick={() => setPaying(b)}>Pay</Button>}
                        {isAdmin && ['draft', 'pending_approval', 'approved'].includes(b.status) && <Button size="sm" variant="ghost" disabled={act.busy} onClick={() => action(b, 'void')}>Void</Button>}
                      </div>
                    </Td>
                  </Tr>
                );
              })}
            </tbody>
          </Table>
        )}
      </Card>

      <AddBillModal open={addOpen} onClose={() => setAddOpen(false)} onCreated={() => { setAddOpen(false); setTab('inbox'); bills.reload(); }} />
      <PayBillModal bill={paying} onClose={() => setPaying(null)} onPaid={() => { setPaying(null); bills.reload(); }} />
    </>
  );
}

// ---------------- Add bill ----------------

function AddBillModal({ open, onClose, onCreated }: { open: boolean; onClose: () => void; onCreated: () => void }) {
  const recipients = useApi<Recipient[]>(open ? '/recipients' : null);
  const gl = useApi<GlAccount[]>(open ? '/books/accounts' : null);
  const { busy, error, run, setError } = useAction();
  const [vendorId, setVendorId] = useState('');
  const [newVendor, setNewVendor] = useState(false);
  const [vName, setVName] = useState('');
  const [vRouting, setVRouting] = useState('');
  const [vAccount, setVAccount] = useState('');
  const [invoiceNo, setInvoiceNo] = useState('');
  const [amount, setAmount] = useState<number | null>(null);
  const [issue, setIssue] = useState(todayIso());
  const [due, setDue] = useState(addDaysIso(todayIso(), 30));
  const [glId, setGlId] = useState('');
  const [memo, setMemo] = useState('');
  const [attachment, setAttachment] = useState('');
  const [submit, setSubmit] = useState(true);
  const [k, setK] = useState(0);

  useEffect(() => {
    if (!open) return;
    setVendorId(''); setNewVendor(false); setVName(''); setVRouting(''); setVAccount(''); setInvoiceNo(''); setAmount(null);
    setIssue(todayIso()); setDue(addDaysIso(todayIso(), 30)); setGlId(''); setMemo(''); setAttachment(''); setSubmit(true); setError(null); setK((x) => x + 1);
  }, [open, setError]);

  const vendors = (recipients.data ?? []).filter((r) => r.is_vendor);
  const expenses = (gl.data ?? []).filter((g) => g.type === 'expense' && !g.archived);

  const pickVendor = (id: string) => {
    if (id === '__new') { setNewVendor(true); setVendorId(''); return; }
    setVendorId(id);
    const v = vendors.find((x) => x.id === id);
    if (v?.default_gl_account_id && !glId) setGlId(v.default_gl_account_id);
  };

  const save = () => run(async () => {
    if (!amount) throw new Error('Enter the bill amount');
    let vid = vendorId;
    if (newVendor) {
      if (!vName.trim()) throw new Error('Enter the vendor name');
      if (vRouting && !/^\d{9}$/.test(vRouting)) throw new Error('Routing number must be 9 digits');
      if (vAccount && !/^\d{4,17}$/.test(vAccount)) throw new Error('Account number must be 4–17 digits');
      const r = await post<Recipient>('/recipients', {
        name: vName.trim(), type: 'business', country: 'US', is_vendor: true,
        ach_routing: vRouting || null, ach_account: vAccount || null, default_gl_account_id: glId || null,
      });
      vid = r.id;
      setNewVendor(false); setVendorId(r.id); recipients.reload();
    }
    if (!vid) throw new Error('Choose a vendor');
    if (due < issue) throw new Error('Due date is before the issue date');
    await post('/bills', {
      vendor_id: vid, invoice_number: invoiceNo.trim() || null, amount, issue_date: issue, due_date: due,
      gl_account_id: glId || null, memo: memo.trim() || null, attachment_name: attachment || null, submit,
    });
    onCreated();
  });

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Add bill"
      footer={<><Button onClick={onClose}>Cancel</Button><Button variant="primary" disabled={busy} onClick={save}>{busy ? 'Saving…' : submit ? 'Submit for approval' : 'Save draft'}</Button></>}
    >
      {newVendor ? (
        <div className="space-y-3 rounded-xl border border-line bg-surface-2/50 p-4">
          <div className="flex items-center justify-between">
            <span className="text-[13px] font-semibold">New vendor</span>
            <button type="button" className="text-[13px] text-brand-600" onClick={() => setNewVendor(false)}>Choose existing</button>
          </div>
          <Field label="Vendor name"><Input value={vName} onChange={(e) => setVName(e.target.value)} placeholder="Acme Supplies Inc." autoFocus /></Field>
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="ACH routing"><Input inputMode="numeric" maxLength={9} value={vRouting} onChange={(e) => setVRouting(e.target.value.replace(/\D/g, ''))} placeholder="9 digits" /></Field>
            <Field label="ACH account"><Input inputMode="numeric" maxLength={17} value={vAccount} onChange={(e) => setVAccount(e.target.value.replace(/\D/g, ''))} placeholder="Account number" /></Field>
          </div>
        </div>
      ) : (
        <Field label="Vendor">
          <Select value={vendorId} onChange={(e) => pickVendor(e.target.value)}>
            <option value="">{recipients.loading ? 'Loading…' : 'Select vendor'}</option>
            {vendors.map((v) => <option key={v.id} value={v.id}>{v.name}</option>)}
            <option value="__new">+ New vendor…</option>
          </Select>
        </Field>
      )}
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Invoice #"><Input value={invoiceNo} onChange={(e) => setInvoiceNo(e.target.value)} placeholder="Optional" /></Field>
        <Field label="Amount"><MoneyInput key={k} value={amount} onChange={setAmount} /></Field>
        <Field label="Issue date"><Input type="date" value={issue} onChange={(e) => setIssue(e.target.value)} /></Field>
        <Field label="Due date"><Input type="date" value={due} onChange={(e) => setDue(e.target.value)} /></Field>
      </div>
      <Field label="GL category">
        <Select value={glId} onChange={(e) => setGlId(e.target.value)}>
          <option value="">Uncategorized</option>
          {expenses.map((g) => <option key={g.id} value={g.id}>{g.code} · {g.name}</option>)}
        </Select>
      </Field>
      <Field label="Memo"><Textarea rows={2} value={memo} onChange={(e) => setMemo(e.target.value)} placeholder="What is this bill for?" /></Field>
      <Field label="Attachment" hint={attachment ? `Attached: ${attachment}` : 'PDF or image of the invoice'}>
        <input
          key={k}
          type="file"
          accept=".pdf,image/*"
          onChange={(e) => setAttachment(e.target.files?.[0]?.name ?? '')}
          className="block w-full text-[13px] text-muted file:mr-3 file:rounded-md file:border file:border-line file:bg-surface file:px-3 file:py-1.5 file:text-ink hover:file:bg-surface-2"
        />
      </Field>
      <label className="flex items-center gap-2">
        <input type="checkbox" className="accent-brand-600" checked={submit} onChange={(e) => setSubmit(e.target.checked)} />
        <span>Submit for approval</span>
      </label>
      <ErrorNote error={error ?? recipients.error ?? gl.error} />
    </Modal>
  );
}

// ---------------- Pay bill ----------------

function PayBillModal({ bill, onClose, onPaid }: { bill: Bill | null; onClose: () => void; onPaid: () => void }) {
  const open = !!bill;
  const accounts = useApi<Account[]>(open ? '/accounts' : null);
  const { busy, error, run, setError } = useAction();
  const [from, setFrom] = useState('');
  const [rail, setRail] = useState<(typeof PAY_RAILS)[number]>('ach');
  const [payDate, setPayDate] = useState('');

  useEffect(() => {
    if (!bill) return;
    const t = todayIso();
    setPayDate(bill.due_date < t ? t : bill.due_date); setRail('ach'); setError(null);
  }, [bill, setError]);

  const sources = (accounts.data ?? []).filter((a) => a.type === 'checking' && a.status === 'open');
  useEffect(() => { if (!from && sources[0]) setFrom(sources[0].id); }, [sources, from]);
  const src = sources.find((a) => a.id === from);

  const pay = () => run(async () => {
    if (!bill || !from) throw new Error('Choose an account');
    await post<{ bill: Bill; payment: Payment }>(`/bills/${bill.id}/pay`, { account_id: from, rail, scheduled_for: payDate || null });
    onPaid();
  });

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={bill ? `Pay ${bill.vendor_name}` : 'Pay bill'}
      footer={<><Button onClick={onClose}>Cancel</Button><Button variant="primary" disabled={busy} onClick={pay}>{busy ? 'Scheduling…' : payDate && payDate > todayIso() ? `Schedule ${money(bill?.amount)}` : `Pay ${money(bill?.amount)}`}</Button></>}
    >
      {bill && (
        <div className="flex items-center justify-between rounded-lg bg-surface-2 px-4 py-3">
          <div>
            <div className="font-medium">{bill.vendor_name}</div>
            <div className="text-[12px] text-muted">{bill.invoice_number ? `#${bill.invoice_number} · ` : ''}Due {date(bill.due_date)}</div>
          </div>
          <Money cents={bill.amount} className="text-lg font-semibold" />
        </div>
      )}
      <Field label="Pay from" hint={src ? `${money(src.balance.available)} available` : undefined}>
        <Select value={from} onChange={(e) => setFrom(e.target.value)}>
          {sources.map((a) => <option key={a.id} value={a.id}>{a.name} ••{a.account_number.slice(-4)}</option>)}
        </Select>
      </Field>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Payment method">
          <Select value={rail} onChange={(e) => setRail(e.target.value as typeof rail)}>
            {PAY_RAILS.map((r) => <option key={r} value={r}>{RAIL_LABELS[r]}</option>)}
          </Select>
        </Field>
        <Field label="Pay date"><Input type="date" min={todayIso()} value={payDate} onChange={(e) => setPayDate(e.target.value)} /></Field>
      </div>
      {rail === 'intl_wire' && <Badge tone="yellow">Requires SWIFT/IBAN details on the vendor</Badge>}
      <ErrorNote error={error ?? accounts.error} />
    </Modal>
  );
}
