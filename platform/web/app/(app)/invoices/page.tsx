'use client';

import { Suspense, useEffect, useMemo, useState } from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { post, useApi } from '@/lib/api';
import { addDaysIso, date, money, todayIso } from '@/lib/format';
import type { Account, GlAccount, Invoice, Recipient } from '@/lib/types';
import {
  Badge, Button, Card, Drawer, Empty, ErrorNote, Field, Input, KeyValue, Loading, Modal, Money, MoneyInput, PageHeader, Select, Stat, StatusBadge, Table, Tabs, Td, Textarea, Th, Tr, cx, useAction,
} from '@/components/ui';

type Inv = Invoice & { paid_at?: string | null; sent_at?: string | null };
type Tab = 'all' | 'draft' | 'outstanding' | 'overdue' | 'paid';

const inTab = (i: Inv, t: Tab) =>
  t === 'all' ? true : t === 'draft' ? i.status === 'draft' : t === 'outstanding' ? i.status === 'sent' : t === 'overdue' ? i.status === 'sent' && i.overdue : i.status === 'paid';
const payLink = (i: Inv) => `${window.location.origin}/pay/${i.public_token}`;

export default function InvoicesPage() {
  return (
    <Suspense fallback={<Loading />}>
      <Invoices />
    </Suspense>
  );
}

function Invoices() {
  const params = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const invoices = useApi<Inv[]>('/invoices');
  const [tab, setTab] = useState<Tab>('all');
  const [newOpen, setNewOpen] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  useEffect(() => {
    if (params.get('new') === '1') setNewOpen(true);
  }, [params]);
  const closeNew = () => {
    setNewOpen(false);
    if (params.get('new')) router.replace(pathname);
  };

  const list = invoices.data ?? [];
  const balance = (i: Inv) => i.total - i.amount_paid;
  const outstanding = list.filter((i) => i.status === 'sent');
  const overdue = outstanding.filter((i) => i.overdue);
  const since = addDaysIso(todayIso(), -30);
  const paid30 = list.filter((i) => i.status === 'paid' && (i.paid_at ?? i.issue_date).slice(0, 10) >= since);
  const rows = useMemo(() => list.filter((i) => inTab(i, tab)), [list, tab]);

  return (
    <>
      <PageHeader
        title="Invoicing"
        subtitle="Bill customers and get paid by ACH or card."
        actions={<Button variant="primary" onClick={() => setNewOpen(true)}>New invoice</Button>}
      />

      <div className="mb-6 grid gap-4 sm:grid-cols-3">
        <Stat label="Outstanding" value={money(outstanding.reduce((s, i) => s + balance(i), 0))} hint={`${outstanding.length} open invoice${outstanding.length === 1 ? '' : 's'}`} />
        <Stat label="Overdue" value={money(overdue.reduce((s, i) => s + balance(i), 0))} tone={overdue.length ? 'neg' : undefined} hint={`${overdue.length} invoice${overdue.length === 1 ? '' : 's'}`} />
        <Stat label="Paid · last 30 days" value={money(paid30.reduce((s, i) => s + i.total, 0))} tone="pos" hint={`${paid30.length} invoice${paid30.length === 1 ? '' : 's'}`} />
      </div>

      <Tabs<Tab>
        value={tab}
        onChange={setTab}
        tabs={([['all', 'All'], ['draft', 'Draft'], ['outstanding', 'Outstanding'], ['overdue', 'Overdue'], ['paid', 'Paid']] as [Tab, string][]).map(([v, l]) => ({
          value: v, label: <span>{l} <span className="ml-1 text-[12px] text-muted">{list.filter((i) => inTab(i, v)).length}</span></span>,
        }))}
      />

      <ErrorNote error={invoices.error} />
      <Card padded={false}>
        {invoices.loading && !invoices.data ? <Loading /> : !rows.length ? (
          <Empty title="No invoices here" hint="Create an invoice and share a payment link with your customer." action={<Button onClick={() => setNewOpen(true)}>New invoice</Button>} />
        ) : (
          <Table>
            <thead>
              <tr><Th>Customer</Th><Th className="hidden sm:table-cell">Number</Th><Th className="hidden md:table-cell">Issued</Th><Th>Due</Th><Th right>Amount</Th><Th className="hidden sm:table-cell">Status</Th></tr>
            </thead>
            <tbody>
              {rows.map((i) => (
                <Tr key={i.id} onClick={() => setSelectedId(i.id)}>
                  <Td>
                    <div className="font-medium">{i.customer_name}</div>
                    <div className="text-[12px] text-muted sm:hidden">{i.number} · {i.overdue && i.status === 'sent' ? 'Overdue' : i.status}</div>
                  </Td>
                  <Td className="hidden text-muted sm:table-cell">{i.number}</Td>
                  <Td className="hidden whitespace-nowrap text-muted md:table-cell">{date(i.issue_date)}</Td>
                  <Td className={cx('whitespace-nowrap', i.status === 'sent' && i.overdue && 'font-medium text-neg')}>{date(i.due_date)}</Td>
                  <Td right>
                    <Money cents={i.total} className="font-medium" />
                    {i.amount_paid > 0 && i.status !== 'paid' && <div className="text-[11px] text-muted">{money(balance(i))} due</div>}
                  </Td>
                  <Td className="hidden sm:table-cell">{i.status === 'sent' && i.overdue ? <StatusBadge status="overdue" /> : <StatusBadge status={i.status} />}</Td>
                </Tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>

      <NewInvoiceModal open={newOpen} onClose={closeNew} onCreated={(inv) => { closeNew(); invoices.reload(); setSelectedId(inv.id); }} />
      <Drawer open={!!selectedId} onClose={() => setSelectedId(null)} title="Invoice">
        {selectedId && <InvoiceDetail key={selectedId} id={selectedId} onChanged={invoices.reload} />}
      </Drawer>
    </>
  );
}

// ---------------- Detail drawer ----------------

function InvoiceDetail({ id, onChanged }: { id: string; onChanged: () => void }) {
  const inv = useApi<Inv>(`/invoices/${id}`);
  const act = useAction();
  const [copied, setCopied] = useState(false);

  if (inv.error) return <ErrorNote error={inv.error} />;
  if (!inv.data) return <Loading />;
  const i = inv.data;
  const due = i.total - i.amount_paid;

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(payLink(i));
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      window.prompt('Copy the payment link', payLink(i));
    }
  };
  const doAction = (verb: 'send' | 'void') => act.run(async () => {
    if (verb === 'void' && !confirm(`Void ${i.number} for ${money(i.total)}? The customer will no longer be able to pay it.`)) return;
    await post(`/invoices/${i.id}/${verb}`);
    inv.reload(); onChanged();
  });

  return (
    <div className="space-y-6">
      <div>
        <div className="flex items-start justify-between gap-3">
          <div>
            <div className="text-[13px] text-muted">{i.number}</div>
            <div className="text-lg font-semibold">{i.customer_name}</div>
            {i.customer_email && <div className="text-[13px] text-muted">{i.customer_email}</div>}
          </div>
          {i.status === 'sent' && i.overdue ? <StatusBadge status="overdue" /> : <StatusBadge status={i.status} />}
        </div>
        <div className="mt-3"><Money cents={i.total} className="text-3xl font-semibold tracking-tight" /></div>
        {i.amount_paid > 0 && i.status !== 'paid' && <div className="text-[13px] text-muted">{money(i.amount_paid)} paid · {money(due)} remaining</div>}
      </div>

      <div className="flex flex-wrap gap-2">
        {i.status === 'draft' && <Button variant="primary" disabled={act.busy} onClick={() => doAction('send')}>Send invoice</Button>}
        {i.status !== 'void' && <Button onClick={copy}>{copied ? 'Copied ✓' : 'Copy payment link'}</Button>}
        {i.status !== 'void' && (
          <a href={`/pay/${i.public_token}`} target="_blank" rel="noreferrer" className="inline-flex h-9 items-center rounded-lg border border-line bg-surface px-3.5 font-medium hover:bg-surface-2">Open hosted page ↗</a>
        )}
      </div>
      <ErrorNote error={act.error} />

      <KeyValue rows={[
        ['Issued', date(i.issue_date)],
        ['Due', <span key="d" className={cx(i.status === 'sent' && i.overdue && 'text-neg')}>{date(i.due_date)}</span>],
        ...(i.sent_at ? [['Sent', date(i.sent_at)] as [string, string]] : []),
        ...(i.paid_at ? [['Paid', date(i.paid_at)] as [string, string]] : []),
      ]} />

      <div>
        <div className="mb-2 font-semibold">Line items</div>
        <div className="-mx-5">
          <Table>
            <thead><tr><Th>Description</Th><Th right>Qty</Th><Th right>Price</Th><Th right>Amount</Th></tr></thead>
            <tbody>
              {(i.lines ?? []).map((l) => (
                <Tr key={l.id}>
                  <Td>{l.description}</Td>
                  <Td right>{l.quantity}</Td>
                  <Td right>{money(l.unit_price)}</Td>
                  <Td right>{money(l.amount)}</Td>
                </Tr>
              ))}
              <Tr><Td colSpan={3} className="text-right font-semibold">Total</Td><Td right className="font-semibold">{money(i.total)}</Td></Tr>
            </tbody>
          </Table>
        </div>
      </div>

      {i.memo && <div><div className="mb-1 font-semibold">Memo</div><p className="text-muted">{i.memo}</p></div>}

      {(i.status === 'draft' || (i.status === 'sent' && i.amount_paid === 0)) && (
        <div className="border-t border-line pt-4">
          <Button variant="danger" disabled={act.busy} onClick={() => doAction('void')}>Void invoice</Button>
        </div>
      )}
    </div>
  );
}

// ---------------- New invoice ----------------

type Line = { key: number; description: string; quantity: string; unit_price: number | null; gl: string };
let lineKey = 0;
const blankLine = (): Line => ({ key: ++lineKey, description: '', quantity: '1', unit_price: null, gl: '' });

function NewInvoiceModal({ open, onClose, onCreated }: { open: boolean; onClose: () => void; onCreated: (i: Inv) => void }) {
  const recipients = useApi<Recipient[]>(open ? '/recipients' : null);
  const accounts = useApi<Account[]>(open ? '/accounts' : null);
  const gl = useApi<GlAccount[]>(open ? '/books/accounts' : null);
  const { busy, error, run, setError } = useAction();
  const [customerId, setCustomerId] = useState('');
  const [newCust, setNewCust] = useState(false);
  const [cName, setCName] = useState('');
  const [cEmail, setCEmail] = useState('');
  const [issue, setIssue] = useState(todayIso());
  const [terms, setTerms] = useState<number | null>(30);
  const [due, setDue] = useState(addDaysIso(todayIso(), 30));
  const [deposit, setDeposit] = useState('');
  const [lines, setLines] = useState<Line[]>([blankLine()]);
  const [memo, setMemo] = useState('');

  useEffect(() => {
    if (!open) return;
    setCustomerId(''); setNewCust(false); setCName(''); setCEmail(''); setIssue(todayIso()); setTerms(30); setDue(addDaysIso(todayIso(), 30));
    setLines([blankLine()]); setMemo('Thank you for your business.'); setError(null);
  }, [open, setError]);

  const customers = (recipients.data ?? []).filter((r) => r.is_customer);
  const deposits = (accounts.data ?? []).filter((a) => a.type === 'checking' && a.status === 'open');
  const revenue = (gl.data ?? []).filter((g) => g.type === 'revenue' && !g.archived);
  useEffect(() => { if (!deposit && deposits[0]) setDeposit(deposits[0].id); }, [deposits, deposit]);

  const qty = (l: Line) => { const n = Number(l.quantity); return Number.isFinite(n) && n > 0 ? n : 0; };
  const lineAmount = (l: Line) => Math.round(qty(l) * (l.unit_price ?? 0));
  const total = lines.reduce((s, l) => s + lineAmount(l), 0);
  const setLine = (key: number, p: Partial<Line>) => setLines((ls) => ls.map((l) => (l.key === key ? { ...l, ...p } : l)));

  const setIssueDate = (d: string) => { setIssue(d); if (terms != null && d) setDue(addDaysIso(d, terms)); };
  const pickTerms = (n: number) => { setTerms(n); if (issue) setDue(addDaysIso(issue, n)); };

  const save = (send: boolean) => run(async () => {
    const good = lines.filter((l) => l.description.trim() || l.unit_price);
    if (!good.length) throw new Error('Add at least one line item');
    for (const l of good) {
      if (!l.description.trim()) throw new Error('Every line needs a description');
      if (!qty(l)) throw new Error(`Quantity for "${l.description}" must be greater than zero`);
      if (!l.unit_price) throw new Error(`Enter a unit price for "${l.description}"`);
    }
    if (!deposit) throw new Error('Choose a deposit account');
    if (due < issue) throw new Error('Due date is before the issue date');
    let cid = customerId;
    if (newCust) {
      if (!cName.trim()) throw new Error('Enter the customer name');
      const r = await post<Recipient>('/recipients', { name: cName.trim(), email: cEmail.trim() || null, type: 'business', country: 'US', is_customer: true });
      cid = r.id;
      setNewCust(false); setCustomerId(r.id); recipients.reload();
    }
    if (!cid) throw new Error('Choose a customer');
    const inv = await post<Inv>('/invoices', {
      customer_id: cid, issue_date: issue, due_date: due, deposit_account_id: deposit, memo: memo.trim() || null, send,
      lines: good.map((l) => ({ description: l.description.trim(), quantity: qty(l), unit_price: l.unit_price!, gl_account_id: l.gl || null })),
    });
    onCreated(inv);
  });

  return (
    <Modal
      open={open}
      onClose={onClose}
      wide
      title="New invoice"
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button disabled={busy} onClick={() => save(false)}>Save draft</Button>
          <Button variant="primary" disabled={busy} onClick={() => save(true)}>{busy ? 'Saving…' : 'Save & send'}</Button>
        </>
      }
    >
      <div className="grid gap-4 sm:grid-cols-2">
        {newCust ? (
          <div className="space-y-3 rounded-xl border border-line bg-surface-2/50 p-4 sm:col-span-2">
            <div className="flex items-center justify-between">
              <span className="text-[13px] font-semibold">New customer</span>
              <button type="button" className="text-[13px] text-brand-600" onClick={() => setNewCust(false)}>Choose existing</button>
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Name"><Input autoFocus value={cName} onChange={(e) => setCName(e.target.value)} placeholder="Customer Co." /></Field>
              <Field label="Billing email"><Input type="email" value={cEmail} onChange={(e) => setCEmail(e.target.value)} placeholder="ap@customer.com" /></Field>
            </div>
          </div>
        ) : (
          <Field label="Customer">
            <Select value={customerId} onChange={(e) => (e.target.value === '__new' ? (setNewCust(true), setCustomerId('')) : setCustomerId(e.target.value))}>
              <option value="">{recipients.loading ? 'Loading…' : 'Select customer'}</option>
              {customers.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              <option value="__new">+ New customer…</option>
            </Select>
          </Field>
        )}
        <Field label="Deposit to">
          <Select value={deposit} onChange={(e) => setDeposit(e.target.value)}>
            {deposits.map((a) => <option key={a.id} value={a.id}>{a.name} ••{a.account_number.slice(-4)}</option>)}
          </Select>
        </Field>
        <Field label="Issue date"><Input type="date" value={issue} onChange={(e) => setIssueDate(e.target.value)} /></Field>
        <Field label="Due date">
          <div className="flex gap-2">
            <Input type="date" value={due} onChange={(e) => { setDue(e.target.value); setTerms(null); }} className="min-w-0" />
            <div className="flex shrink-0 rounded-lg border border-line p-0.5">
              {[15, 30, 45].map((n) => (
                <button key={n} type="button" onClick={() => pickTerms(n)} className={cx('rounded-md px-2 text-[12px] font-medium', terms === n ? 'bg-brand-600 text-white' : 'text-muted hover:text-ink')}>
                  Net {n}
                </button>
              ))}
            </div>
          </div>
        </Field>
      </div>

      <div>
        <div className="mb-2 text-[13px] font-medium">Line items</div>
        <div className="space-y-3">
          <div className="hidden grid-cols-[1fr_70px_130px_160px_100px_28px] gap-2 text-[12px] uppercase tracking-wide text-muted md:grid">
            <span>Description</span><span>Qty</span><span>Unit price</span><span>Revenue account</span><span className="text-right">Amount</span><span />
          </div>
          {lines.map((l) => (
            <div key={l.key} className="grid grid-cols-2 gap-2 rounded-lg border border-line p-3 md:grid-cols-[1fr_70px_130px_160px_100px_28px] md:items-center md:border-0 md:p-0">
              <Input className="col-span-2 md:col-span-1" placeholder="Description" value={l.description} onChange={(e) => setLine(l.key, { description: e.target.value })} />
              <Input inputMode="decimal" placeholder="Qty" value={l.quantity} onChange={(e) => setLine(l.key, { quantity: e.target.value })} />
              <MoneyInput value={l.unit_price} onChange={(c) => setLine(l.key, { unit_price: c })} />
              <Select className="col-span-2 md:col-span-1" value={l.gl} onChange={(e) => setLine(l.key, { gl: e.target.value })}>
                <option value="">Default revenue</option>
                {revenue.map((g) => <option key={g.id} value={g.id}>{g.code} · {g.name}</option>)}
              </Select>
              <div className="text-right font-medium tabular md:col-span-1">{money(lineAmount(l))}</div>
              <button
                type="button"
                aria-label="Remove line"
                disabled={lines.length === 1}
                onClick={() => setLines((ls) => ls.filter((x) => x.key !== l.key))}
                className="justify-self-end rounded p-1 text-muted hover:bg-surface-2 hover:text-neg disabled:opacity-30"
              >
                ✕
              </button>
            </div>
          ))}
        </div>
        <div className="mt-3 flex items-center justify-between">
          <Button size="sm" onClick={() => setLines((ls) => [...ls, blankLine()])}>+ Add line</Button>
          <div className="text-right">
            <div className="text-[12px] text-muted">Total</div>
            <div className="text-xl font-semibold tabular">{money(total)}</div>
          </div>
        </div>
      </div>

      <Field label="Memo" hint="Shown to the customer on the invoice">
        <Textarea rows={2} value={memo} onChange={(e) => setMemo(e.target.value)} />
      </Field>
      {gl.error && <Badge tone="yellow">Revenue accounts unavailable — lines will use the default revenue account.</Badge>}
      <ErrorNote error={error ?? recipients.error ?? accounts.error} />
    </Modal>
  );
}
