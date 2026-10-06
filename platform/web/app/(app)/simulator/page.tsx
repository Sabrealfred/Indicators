'use client';

import { useState, type ReactNode } from 'react';
import { post, useApi } from '@/lib/api';
import { can, useSession } from '@/lib/session';
import { RAIL_LABELS, dateTime, money, shortDate, titleCase } from '@/lib/format';
import type { Account, Card as CardT, Payment, Transaction } from '@/lib/types';
import { Badge, Button, Card, Empty, ErrorNote, Field, Input, Loading, Money, MoneyInput, PageHeader, Select, StatusBadge, Table, Td, Th, useAction } from '@/components/ui';

type Notice = { tone: 'ok' | 'bad'; text: ReactNode } | null;

function NoticeBar({ n }: { n: Notice }) {
  if (!n) return null;
  return (
    <div className={n.tone === 'ok'
      ? 'rounded-lg border border-emerald-500/30 bg-emerald-500/5 px-3 py-2 text-[13px] text-pos'
      : 'rounded-lg border border-red-500/30 bg-red-500/5 px-3 py-2 text-[13px] text-neg'}>
      {n.text}
    </div>
  );
}

function describeTick(r: Record<string, unknown>) {
  const parts = Object.entries(r)
    .filter(([, v]) => typeof v === 'number')
    .map(([k, v]) => `${v} ${k.replace(/_/g, ' ')}`);
  return parts.length ? parts.join(' · ') : 'Nothing to process';
}

// ---------- Clock ----------

function Clock({ onChange }: { onChange: () => void }) {
  const clock = useApi<{ now: string }>('/sim/clock');
  const a = useAction();
  const [result, setResult] = useState<string | null>(null);

  const advance = async (hours: number, label: string) => {
    const r = await a.run(() => post<{ now: string; last_tick: Record<string, unknown> }>('/sim/advance', { hours }));
    if (r) {
      setResult(`Advanced ${label} → ${dateTime(r.now)}. Last back-office run: ${describeTick(r.last_tick)}.`);
      clock.reload();
      onChange();
    }
  };
  const tick = async () => {
    const r = await a.run(() => post<Record<string, unknown>>('/sim/tick'));
    if (r) {
      setResult(`Back office ran: ${describeTick(r)}.`);
      clock.reload();
      onChange();
    }
  };

  const now = clock.data ? new Date(clock.data.now) : null;
  return (
    <Card title="Simulated clock">
      <div className="mb-4">
        <div className="text-[13px] text-muted">Bank time (UTC)</div>
        <div className="text-2xl font-semibold tracking-tight tabular">
          {now ? now.toLocaleString('en-US', { weekday: 'short', month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit', timeZone: 'UTC' }) : '…'}
        </div>
      </div>
      <div className="flex flex-wrap gap-2">
        {([[1, '1 hour'], [24, '1 day'], [24 * 7, '1 week'], [24 * 30, '30 days']] as const).map(([h, label]) => (
          <Button key={h} onClick={() => advance(h, label)} disabled={a.busy}>+{label}</Button>
        ))}
        <Button variant="primary" onClick={tick} disabled={a.busy}>{a.busy ? 'Running…' : 'Run back office now'}</Button>
      </div>
      <p className="mt-3 text-[12px] text-muted">
        Advancing time settles ACH/wires/checks on their schedules, captures card authorizations older than a day, accrues treasury interest and runs month-end (statements, cashback).
      </p>
      <div className="mt-3 space-y-2">
        <ErrorNote error={a.error || clock.error} />
        {result && <NoticeBar n={{ tone: 'ok', text: result }} />}
      </div>
    </Card>
  );
}

// ---------- Incoming payment ----------

function Incoming({ accounts, onChange }: { accounts: Account[]; onChange: () => void }) {
  const deposit = accounts.filter((a) => a.type !== 'credit' && a.status === 'open');
  const [accountId, setAccountId] = useState('');
  const [rail, setRail] = useState('ach');
  const [cp, setCp] = useState('');
  const [amount, setAmount] = useState<number | null>(null);
  const [desc, setDesc] = useState('');
  const [formKey, setFormKey] = useState(0);
  const [notice, setNotice] = useState<Notice>(null);
  const a = useAction();
  const acct = accountId || deposit[0]?.id || '';
  const ok = acct && cp.trim() && amount && amount > 0;

  const submit = async () => {
    const r = await a.run(() => post<{ transaction_id: string }>('/sim/incoming', {
      account_id: acct, rail, counterparty_name: cp.trim(), amount, ...(desc.trim() ? { description: desc.trim() } : {}),
    }));
    if (r) {
      setNotice({ tone: 'ok', text: `${money(amount)} ${RAIL_LABELS[rail] ?? rail} from ${cp.trim()} received (${r.transaction_id}).` });
      setCp(''); setAmount(null); setDesc(''); setFormKey((k) => k + 1);
      onChange();
    }
  };

  return (
    <Card title="Incoming payment">
      <div className="space-y-3">
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Deposit to">
            <Select value={acct} onChange={(e) => setAccountId(e.target.value)}>
              {deposit.map((x) => <option key={x.id} value={x.id}>{x.name} ••{x.account_number.slice(-4)}</option>)}
            </Select>
          </Field>
          <Field label="Rail">
            <Select value={rail} onChange={(e) => setRail(e.target.value)}>
              <option value="ach">ACH</option>
              <option value="wire">Domestic wire</option>
              <option value="intl_wire">International wire</option>
              <option value="check">Check deposit</option>
            </Select>
          </Field>
        </div>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="From (counterparty)"><Input value={cp} onChange={(e) => setCp(e.target.value)} placeholder="e.g. Nova Health Systems" /></Field>
          <Field label="Amount"><MoneyInput key={formKey} value={amount} onChange={setAmount} /></Field>
        </div>
        <Field label="Description"><Input value={desc} onChange={(e) => setDesc(e.target.value)} placeholder="Incoming payment" /></Field>
        <ErrorNote error={a.error} />
        <NoticeBar n={notice} />
        <Button variant="primary" onClick={submit} disabled={!ok || a.busy}>{a.busy ? 'Sending…' : 'Send money in'}</Button>
      </div>
    </Card>
  );
}

// ---------- Card purchase ----------

function Purchase({ cards, onChange }: { cards: CardT[]; onChange: () => void }) {
  const cats = useApi<string[]>('/merchant-categories');
  const usable = cards.filter((c) => c.status !== 'canceled');
  const [cardId, setCardId] = useState('');
  const [merchant, setMerchant] = useState('');
  const [amount, setAmount] = useState<number | null>(null);
  const [category, setCategory] = useState('');
  const [notice, setNotice] = useState<Notice>(null);
  const a = useAction();
  const card = cardId || usable[0]?.id || '';
  const ok = card && merchant.trim() && amount && amount > 0;

  const submit = async () => {
    const r = await a.run(() => post<{ approved: boolean; decline_reason: string | null; transaction_id: string }>(`/sim/cards/${card}/authorize`, {
      merchant: merchant.trim(), amount, ...(category ? { merchant_category: category } : {}),
    }));
    if (r) {
      setNotice(r.approved
        ? { tone: 'ok', text: <>✓ Approved — {money(amount)} at {merchant.trim()} is now a pending authorization.</> }
        : { tone: 'bad', text: <>✕ Declined — {r.decline_reason ?? 'no reason given'}</> });
      onChange();
    }
  };

  return (
    <Card title="Card purchase">
      <div className="space-y-3">
        <Field label="Card">
          <Select value={card} onChange={(e) => setCardId(e.target.value)}>
            {usable.map((c) => (
              <option key={c.id} value={c.id}>
                {c.nickname} ••{c.last4}{c.status === 'frozen' ? ' (frozen)' : ''}{c.spend_limit ? ` · ${money(c.spend_limit)}/${c.limit_interval ?? ''}` : ''}
              </option>
            ))}
          </Select>
        </Field>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Merchant"><Input value={merchant} onChange={(e) => setMerchant(e.target.value)} placeholder="e.g. Delta Air Lines" /></Field>
          <Field label="Amount"><MoneyInput value={amount} onChange={setAmount} /></Field>
        </div>
        <Field label="Merchant category" hint="Used for blocked-category checks and auto-categorization.">
          <Select value={category} onChange={(e) => setCategory(e.target.value)}>
            <option value="">None</option>
            {(cats.data ?? []).map((c) => <option key={c} value={c}>{titleCase(c)}</option>)}
          </Select>
        </Field>
        <ErrorNote error={a.error} />
        <NoticeBar n={notice} />
        <Button variant="primary" onClick={submit} disabled={!ok || a.busy}>{a.busy ? 'Authorizing…' : 'Swipe card'}</Button>
      </div>
    </Card>
  );
}

// ---------- Pending authorizations ----------

function PendingRow({ t, onChange }: { t: Transaction; onChange: () => void }) {
  const [amt, setAmt] = useState<number | null>(null);
  const a = useAction();
  const capture = async () => {
    const r = await a.run(() => post(`/sim/transactions/${t.id}/capture`, amt ? { amount: amt } : {}));
    if (r) onChange();
  };
  const voidIt = async () => {
    if (!confirm(`Void the ${money(-t.amount)} authorization at ${t.counterparty_name}?`)) return;
    const r = await a.run(() => post(`/sim/transactions/${t.id}/void`));
    if (r) onChange();
  };
  return (
    <tr className="hover:bg-surface-2">
      <Td className="whitespace-nowrap py-2 text-muted">{dateTime(t.created_at)}</Td>
      <Td className="py-2">
        <div className="font-medium">{t.counterparty_name}</div>
        <div className="text-[12px] text-muted">••{t.card_last4}{t.cardholder ? ` · ${t.cardholder}` : ''}</div>
      </Td>
      <Td right className="py-2"><Money cents={-t.amount} /></Td>
      <Td className="py-2">
        <div className="flex items-center justify-end gap-2">
          <div className="w-28"><MoneyInput value={amt} onChange={setAmt} placeholder={(-t.amount / 100).toFixed(2)} /></div>
          <Button size="sm" variant="primary" onClick={capture} disabled={a.busy}>Capture</Button>
          <Button size="sm" variant="danger" onClick={voidIt} disabled={a.busy}>Void</Button>
        </div>
        {a.error && <div className="mt-1 text-right text-[12px] text-neg">{a.error}</div>}
      </Td>
    </tr>
  );
}

function PostedRow({ t, onChange }: { t: Transaction; onChange: () => void }) {
  const a = useAction();
  const refund = async () => {
    if (!confirm(`Refund ${money(-t.amount)} from ${t.counterparty_name}?`)) return;
    const r = await a.run(() => post(`/sim/transactions/${t.id}/refund`));
    if (r) onChange();
  };
  return (
    <tr className="hover:bg-surface-2">
      <Td className="whitespace-nowrap py-2 text-muted">{shortDate(t.posted_at ?? t.created_at)}</Td>
      <Td className="py-2">
        <div className="font-medium">{t.counterparty_name}</div>
        <div className="text-[12px] text-muted">••{t.card_last4}{t.category_name ? ` · ${t.category_name}` : ''}</div>
      </Td>
      <Td right className="py-2"><Money cents={-t.amount} /></Td>
      <Td right className="py-2">
        <Button size="sm" onClick={refund} disabled={a.busy}>Refund</Button>
        {a.error && <div className="mt-1 text-[12px] text-neg">{a.error}</div>}
      </Td>
    </tr>
  );
}

function PaymentRow({ p, onChange }: { p: Payment; onChange: () => void }) {
  const a = useAction();
  const ret = async () => {
    if (!confirm(`Have the receiving bank return this ${money(p.amount)} ACH to ${p.counterparty_name ?? 'the recipient'} (R01 insufficient funds)?`)) return;
    const r = await a.run(() => post(`/sim/payments/${p.id}/return`, { reason: 'R01 — Insufficient funds' }));
    if (r) onChange();
  };
  return (
    <tr className="hover:bg-surface-2">
      <Td className="whitespace-nowrap py-2 text-muted">{shortDate(p.created_at)}</Td>
      <Td className="py-2">
        <div className="font-medium">{p.counterparty_name ?? p.to_account_name ?? '—'}</div>
        <div className="text-[12px] text-muted">{RAIL_LABELS[p.rail] ?? p.rail} · {p.account_name}{p.memo ? ` · ${p.memo}` : ''}</div>
      </Td>
      <Td className="py-2"><StatusBadge status={p.status} /></Td>
      <Td right className="py-2"><Money cents={p.amount} /></Td>
      <Td right className="py-2">
        <Button size="sm" variant="danger" onClick={ret} disabled={a.busy}>Return (R01)</Button>
        {a.error && <div className="mt-1 text-[12px] text-neg">{a.error}</div>}
      </Td>
    </tr>
  );
}

// ---------- Page ----------

export default function SimulatorPage() {
  const { user } = useSession();
  const admin = can.admin(user.role);
  const accounts = useApi<Account[]>(admin ? '/accounts' : null);
  const cards = useApi<CardT[]>(admin ? '/cards' : null);
  const pending = useApi<{ total: number; rows: Transaction[] }>(admin ? '/transactions?status=pending&kind=card&limit=50' : null);
  const posted = useApi<{ total: number; rows: Transaction[] }>(admin ? '/transactions?status=posted&kind=card&limit=10' : null);
  const payments = useApi<Payment[]>(admin ? '/payments' : null);

  if (!admin) {
    return (
      <>
        <PageHeader title="Simulator" />
        <Card><Empty title="Admins only" hint="The sandbox control room is available to workspace admins." /></Card>
      </>
    );
  }

  const reloadAll = () => [accounts, cards, pending, posted, payments].forEach((x) => x.reload());
  const ach = (payments.data ?? []).filter((p) => (p.rail === 'ach' || p.rail === 'same_day_ach') && (p.status === 'processing' || p.status === 'completed')).slice(0, 15);
  const err = accounts.error || cards.error || pending.error || posted.error || payments.error;

  return (
    <>
      <PageHeader title="Sandbox control room" subtitle="Drive the outside world: time, counterparties, card network and payment rails." actions={<Button onClick={reloadAll}>Refresh</Button>} />
      <div className="mb-6 rounded-xl border border-brand-500/30 bg-brand-500/5 px-4 py-3 text-[13px]">
        <span className="mr-2"><Badge tone="blue">Sandbox</Badge></span>
        Nothing here moves real money. These controls simulate the bank&apos;s counterparties — customers paying you, merchants charging your cards, the card network capturing and refunding, and receiving banks returning ACH — so you can see how LedgerBank and your books react.
      </div>
      <div className="mb-4"><ErrorNote error={err} /></div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <Clock onChange={reloadAll} />
        {accounts.data ? <Incoming accounts={accounts.data} onChange={reloadAll} /> : <Card title="Incoming payment"><Loading /></Card>}
        {cards.data ? <Purchase cards={cards.data} onChange={reloadAll} /> : <Card title="Card purchase"><Loading /></Card>}

        <Card title="Pending card authorizations" padded={false} actions={pending.data && <Badge tone="yellow">{pending.data.total}</Badge>}>
          {!pending.data ? <Loading /> : !pending.data.rows.length ? <Empty title="No pending authorizations" hint="Swipe a card to create one." /> : (
            <Table>
              <thead><tr><Th>Authorized</Th><Th>Merchant</Th><Th right>Amount</Th><Th right>Capture amount</Th></tr></thead>
              <tbody>{pending.data.rows.map((t) => <PendingRow key={t.id} t={t} onChange={reloadAll} />)}</tbody>
            </Table>
          )}
          <p className="border-t border-line px-5 py-2.5 text-[12px] text-muted">Leave the capture amount blank to capture the authorized amount (tips and fuel can differ).</p>
        </Card>
      </div>

      <div className="mt-6 grid grid-cols-1 gap-6 lg:grid-cols-2">
        <Card title="Recent posted card transactions" padded={false}>
          {!posted.data ? <Loading /> : !posted.data.rows.length ? <Empty title="No posted card transactions" /> : (
            <Table>
              <thead><tr><Th>Posted</Th><Th>Merchant</Th><Th right>Amount</Th><Th right /></tr></thead>
              <tbody>{posted.data.rows.map((t) => <PostedRow key={t.id} t={t} onChange={reloadAll} />)}</tbody>
            </Table>
          )}
        </Card>

        <Card title="ACH payments (returnable)" padded={false}>
          {!payments.data ? <Loading /> : !ach.length ? <Empty title="No processing or completed ACH payments" hint="Send an ACH from Payments to try a return." /> : (
            <Table>
              <thead><tr><Th>Created</Th><Th>Recipient</Th><Th>Status</Th><Th right>Amount</Th><Th right /></tr></thead>
              <tbody>{ach.map((p) => <PaymentRow key={p.id} p={p} onChange={reloadAll} />)}</tbody>
            </Table>
          )}
        </Card>
      </div>
    </>
  );
}
