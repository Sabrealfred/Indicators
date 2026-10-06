'use client';

import { useEffect, useMemo, useState } from 'react';
import { patch, post, useApi } from '@/lib/api';
import { can, useSession } from '@/lib/session';
import { money, shortDate, titleCase } from '@/lib/format';
import type { Account, Card as CardT, TeamMember, Transaction } from '@/lib/types';
import {
  Button, Card, Drawer, Empty, ErrorNote, Field, Input, KeyValue, Loading, Modal, Money, MoneyInput, PageHeader, Select, StatusBadge, Table, Tabs, Td, Tr, cx, useAction,
} from '@/components/ui';
import { CardVisual, ProgressBar, expLabel } from './_card-visual';

type Filter = 'active' | 'frozen' | 'canceled' | 'all';
type Interval = NonNullable<CardT['limit_interval']>;

const INTERVALS: { value: Interval; label: string }[] = [
  { value: 'transaction', label: 'Per transaction' },
  { value: 'daily', label: 'Daily' },
  { value: 'monthly', label: 'Monthly' },
  { value: 'all_time', label: 'All time' },
];
const intervalLabel = (i: CardT['limit_interval']) => INTERVALS.find((x) => x.value === i)?.label ?? '';

export default function CardsPage() {
  const { user } = useSession();
  const isAdmin = can.admin(user.role);
  const cards = useApi<CardT[]>('/cards');
  const team = useApi<TeamMember[]>('/team');
  const [filter, setFilter] = useState<Filter>('active');
  const [issueOpen, setIssueOpen] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const names = useMemo(() => Object.fromEntries((team.data ?? []).map((m) => [m.id, m.name])), [team.data]);
  const list = cards.data ?? [];
  const count = (f: Filter) => (f === 'all' ? list.length : list.filter((c) => c.status === f).length);
  const shown = filter === 'all' ? list : list.filter((c) => c.status === filter);
  const selected = list.find((c) => c.id === selectedId) ?? null;

  return (
    <>
      <PageHeader
        title="Cards"
        subtitle={isAdmin ? 'Virtual and physical cards for your team, with spend controls.' : 'Your company cards.'}
        actions={isAdmin && <Button variant="primary" onClick={() => setIssueOpen(true)}>Issue card</Button>}
      />

      <Tabs<Filter>
        value={filter}
        onChange={setFilter}
        tabs={(['active', 'frozen', 'canceled', 'all'] as Filter[]).map((f) => ({
          value: f,
          label: <span>{titleCase(f)} <span className="ml-1 text-[12px] text-muted">{count(f)}</span></span>,
        }))}
      />

      <ErrorNote error={cards.error} />
      {cards.loading && !cards.data ? (
        <Loading />
      ) : !shown.length ? (
        <Card>
          <Empty
            title={list.length ? `No ${filter === 'all' ? '' : filter + ' '}cards` : 'No cards yet'}
            hint={isAdmin ? 'Issue virtual cards instantly for vendors, subscriptions or teammates.' : 'Ask an admin to issue you a card.'}
            action={isAdmin && !list.length && <Button variant="primary" onClick={() => setIssueOpen(true)}>Issue card</Button>}
          />
        </Card>
      ) : (
        <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
          {shown.map((c) => (
            <div key={c.id}>
              <CardVisual card={c} holder={names[c.user_id]} onClick={() => setSelectedId(c.id)} />
              <div className="mt-2.5 px-1 text-[13px]">
                {c.spend_limit ? (
                  <>
                    <div className="flex justify-between text-muted">
                      <span><Money cents={c.spent_in_window} className="font-medium text-ink" /> spent</span>
                      <span>{money(c.spend_limit)} {intervalLabel(c.limit_interval).toLowerCase()}</span>
                    </div>
                    <ProgressBar value={c.spent_in_window} max={c.spend_limit} className="mt-1.5 h-1.5" />
                  </>
                ) : (
                  <div className="text-muted">No spend limit</div>
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      {isAdmin && (
        <IssueCardModal
          open={issueOpen}
          onClose={() => setIssueOpen(false)}
          team={team.data ?? []}
          onIssued={(c) => { setIssueOpen(false); cards.reload(); team.reload(); setSelectedId(c.id); }}
        />
      )}

      <Drawer open={!!selected} onClose={() => setSelectedId(null)} title={selected ? `${selected.nickname} ••${selected.last4}` : ''}>
        {selected && (
          <CardDetail
            key={selected.id}
            card={selected}
            holder={names[selected.user_id]}
            isAdmin={isAdmin}
            isOwner={selected.user_id === user.id}
            onChanged={() => { cards.reload(); team.reload(); }}
          />
        )}
      </Drawer>
    </>
  );
}

// ---------------- Issue card ----------------

function IssueCardModal({ open, onClose, team, onIssued }: { open: boolean; onClose: () => void; team: TeamMember[]; onIssued: (c: CardT) => void }) {
  const accounts = useApi<Account[]>(open ? '/accounts' : null);
  const cats = useApi<string[]>(open ? '/merchant-categories' : null);
  const { busy, error, run, setError } = useAction();
  const [userId, setUserId] = useState('');
  const [accountId, setAccountId] = useState('');
  const [nickname, setNickname] = useState('');
  const [form, setForm] = useState<'virtual' | 'physical'>('virtual');
  const [limit, setLimit] = useState<number | null>(null);
  const [interval, setInterval_] = useState<Interval>('monthly');
  const [blocked, setBlocked] = useState<string[]>([]);

  const funding = (accounts.data ?? []).filter((a) => (a.type === 'checking' || a.type === 'credit') && a.status === 'open');
  const members = team.filter((m) => m.status === 'active');

  useEffect(() => {
    if (!open) return;
    setNickname(''); setForm('virtual'); setLimit(null); setInterval_('monthly'); setBlocked([]); setError(null);
  }, [open, setError]);
  useEffect(() => { if (!userId && members[0]) setUserId(members[0].id); }, [members, userId]);
  useEffect(() => {
    if (!accountId && funding.length) setAccountId((funding.find((a) => a.type === 'credit') ?? funding[0]).id);
  }, [funding, accountId]);

  const submit = () => run(async () => {
    if (!nickname.trim()) throw new Error('Give the card a nickname');
    if (!userId || !accountId) throw new Error('Choose a cardholder and funding account');
    const c = await post<CardT>('/cards', {
      user_id: userId, account_id: accountId, nickname: nickname.trim(), form,
      spend_limit: limit || null, limit_interval: limit ? interval : null, blocked_categories: blocked,
    });
    onIssued(c);
  });

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Issue card"
      footer={<><Button onClick={onClose}>Cancel</Button><Button variant="primary" disabled={busy} onClick={submit}>{busy ? 'Issuing…' : 'Issue card'}</Button></>}
    >
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Cardholder">
          <Select value={userId} onChange={(e) => setUserId(e.target.value)}>
            {members.map((m) => <option key={m.id} value={m.id}>{m.name} · {titleCase(m.role)}</option>)}
          </Select>
        </Field>
        <Field label="Funding account">
          <Select value={accountId} onChange={(e) => setAccountId(e.target.value)}>
            {funding.map((a) => <option key={a.id} value={a.id}>{a.name} ({a.type === 'credit' ? 'Credit' : 'Checking'})</option>)}
          </Select>
        </Field>
      </div>
      <Field label="Nickname" hint="e.g. AWS, Team offsite, Marketing tools">
        <Input value={nickname} maxLength={40} onChange={(e) => setNickname(e.target.value)} placeholder="Card nickname" />
      </Field>
      <Field label="Card type">
        <div className="grid grid-cols-2 gap-2">
          {(['virtual', 'physical'] as const).map((f) => (
            <button
              key={f}
              type="button"
              onClick={() => setForm(f)}
              className={cx('rounded-lg border px-3 py-2 text-left', form === f ? 'border-brand-500 bg-brand-500/5 ring-2 ring-brand-500/20' : 'border-line hover:bg-surface-2')}
            >
              <div className="font-medium">{titleCase(f)}</div>
              <div className="text-[12px] text-muted">{f === 'virtual' ? 'Ready to use instantly' : 'Shipped in 5–7 business days'}</div>
            </button>
          ))}
        </div>
      </Field>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Spend limit (optional)">
          <MoneyInput value={limit} onChange={setLimit} placeholder="No limit" />
        </Field>
        <Field label="Limit resets">
          <Select value={interval} disabled={!limit} onChange={(e) => setInterval_(e.target.value as Interval)}>
            {INTERVALS.map((i) => <option key={i.value} value={i.value}>{i.label}</option>)}
          </Select>
        </Field>
      </div>
      <CategoryPicker all={cats.data ?? []} value={blocked} onChange={setBlocked} />
      <ErrorNote error={error ?? accounts.error} />
    </Modal>
  );
}

function CategoryPicker({ all, value, onChange }: { all: string[]; value: string[]; onChange: (v: string[]) => void }) {
  return (
    <div>
      <div className="mb-1 text-[13px] font-medium">Blocked merchant categories</div>
      <div className="grid grid-cols-2 gap-x-3 gap-y-1.5 sm:grid-cols-3">
        {all.map((c) => (
          <label key={c} className="flex items-center gap-2 text-[13px]">
            <input
              type="checkbox"
              className="accent-brand-600"
              checked={value.includes(c)}
              onChange={(e) => onChange(e.target.checked ? [...value, c] : value.filter((x) => x !== c))}
            />
            {titleCase(c)}
          </label>
        ))}
      </div>
    </div>
  );
}

// ---------------- Card detail drawer ----------------

type Secret = { pan: string; cvv: string; exp_month: number; exp_year: number };

function CardDetail({ card, holder, isAdmin, isOwner, onChanged }: { card: CardT; holder?: string; isAdmin: boolean; isOwner: boolean; onChanged: () => void }) {
  const tx = useApi<{ total: number; rows: Transaction[] }>(`/transactions?card_id=${card.id}&limit=15`);
  const accounts = useApi<Account[]>(isAdmin ? '/accounts' : null);
  const act = useAction();
  const [secret, setSecret] = useState<Secret | null>(null);
  const [left, setLeft] = useState(0);
  const [editing, setEditing] = useState(false);

  useEffect(() => {
    if (!secret) return;
    const t = setInterval(() => setLeft((s) => {
      if (s <= 1) { setSecret(null); return 0; }
      return s - 1;
    }), 1000);
    return () => clearInterval(t);
  }, [secret]);

  const canManage = isAdmin || isOwner;
  const canceled = card.status === 'canceled';
  const fundingName = accounts.data?.find((a) => a.id === card.account_id)?.name;

  const reveal = () => act.run(async () => {
    const s = await post<Secret>(`/cards/${card.id}/reveal`);
    setSecret(s);
    setLeft(30);
  });
  const setStatus = (status: CardT['status']) => act.run(async () => {
    if (status === 'canceled' && !confirm(`Cancel "${card.nickname}" ••${card.last4}? This cannot be undone.`)) return;
    await patch(`/cards/${card.id}`, { status });
    onChanged();
  });

  return (
    <div className="space-y-6">
      <CardVisual card={card} holder={holder} />

      {canManage && !canceled && (
        <div className="flex flex-wrap gap-2">
          {card.status === 'active'
            ? <Button disabled={act.busy} onClick={() => setStatus('frozen')}>Freeze</Button>
            : <Button disabled={act.busy} onClick={() => setStatus('active')}>Unfreeze</Button>}
          {secret ? <Button onClick={() => setSecret(null)}>Hide details</Button> : <Button disabled={act.busy} onClick={reveal}>Show card details</Button>}
          {isAdmin && <Button onClick={() => setEditing((e) => !e)}>{editing ? 'Close limits' : 'Edit limits'}</Button>}
        </div>
      )}
      <ErrorNote error={act.error} />

      {secret && (
        <div className="rounded-xl border border-line bg-surface-2 p-4">
          <div className="mb-2 flex items-center justify-between text-[12px] text-muted">
            <span>Card details — keep these private</span>
            <span className="tabular">Hides in {left}s</span>
          </div>
          <div className="select-all font-mono text-lg tracking-wider">{secret.pan.replace(/\D/g, '').replace(/(\d{4})(?=\d)/g, '$1 ')}</div>
          <div className="mt-2 flex gap-6 font-mono">
            <div><div className="font-sans text-[12px] text-muted">EXP</div>{expLabel(secret.exp_month, secret.exp_year)}</div>
            <div><div className="font-sans text-[12px] text-muted">CVV</div>{secret.cvv}</div>
          </div>
        </div>
      )}

      <div>
        <div className="mb-2 font-semibold">Spend</div>
        {card.spend_limit ? (
          <>
            <div className="flex items-baseline justify-between">
              <span><Money cents={card.spent_in_window} className="text-lg font-semibold" /> <span className="text-muted">of {money(card.spend_limit)}</span></span>
              <span className="text-[13px] text-muted">{intervalLabel(card.limit_interval)}</span>
            </div>
            <ProgressBar value={card.spent_in_window} max={card.spend_limit} className="mt-2" />
            <div className="mt-1.5 text-[12px] text-muted">{money(Math.max(0, card.spend_limit - card.spent_in_window))} remaining{card.limit_interval === 'transaction' ? ' per transaction' : ''}</div>
          </>
        ) : (
          <div className="text-muted">No spend limit set{card.spent_in_window ? ` · ${money(card.spent_in_window)} spent` : ''}.</div>
        )}
      </div>

      {editing && isAdmin && !canceled && <EditLimits card={card} onSaved={() => { setEditing(false); onChanged(); }} />}

      <KeyValue
        rows={[
          ['Status', <StatusBadge key="s" status={card.status} />],
          ['Cardholder', holder ?? '—'],
          ['Type', titleCase(card.form)],
          ['Network', titleCase(card.network)],
          ['Expires', expLabel(card.exp_month, card.exp_year)],
          ...(fundingName ? [['Funding account', fundingName] as [string, string]] : []),
          ['Blocked categories', card.blocked_categories.length ? card.blocked_categories.map(titleCase).join(', ') : 'None'],
          ['Issued', shortDate(card.created_at)],
        ]}
      />

      <div>
        <div className="mb-2 font-semibold">Recent transactions</div>
        {tx.error ? <ErrorNote error={tx.error} /> : !tx.data ? <Loading /> : !tx.data.rows.length ? (
          <div className="rounded-lg border border-dashed border-line px-4 py-6 text-center text-muted">No transactions on this card yet.</div>
        ) : (
          <div className="-mx-5">
            <Table>
              <tbody>
                {tx.data.rows.map((t) => (
                  <Tr key={t.id}>
                    <Td className="w-16 text-muted">{shortDate(t.created_at)}</Td>
                    <Td>
                      <div className="font-medium">{t.counterparty_name}</div>
                      {t.status !== 'posted' && <div className="mt-0.5"><StatusBadge status={t.status} />{t.decline_reason && <span className="ml-1 text-[12px] text-muted">{t.decline_reason}</span>}</div>}
                    </Td>
                    <Td right><Money cents={t.amount} signed className={t.status === 'declined' ? 'text-muted line-through' : ''} /></Td>
                  </Tr>
                ))}
              </tbody>
            </Table>
          </div>
        )}
      </div>

      {isAdmin && !canceled && (
        <div className="border-t border-line pt-4">
          <Button variant="danger" disabled={act.busy} onClick={() => setStatus('canceled')}>Cancel card</Button>
        </div>
      )}
    </div>
  );
}

function EditLimits({ card, onSaved }: { card: CardT; onSaved: () => void }) {
  const cats = useApi<string[]>('/merchant-categories');
  const { busy, error, run } = useAction();
  const [nickname, setNickname] = useState(card.nickname);
  const [limit, setLimit] = useState<number | null>(card.spend_limit);
  const [interval, setInterval_] = useState<Interval>(card.limit_interval ?? 'monthly');
  const [blocked, setBlocked] = useState<string[]>(card.blocked_categories);

  const save = () => run(async () => {
    if (!nickname.trim()) throw new Error('Nickname is required');
    await patch(`/cards/${card.id}`, {
      nickname: nickname.trim(), spend_limit: limit || null, limit_interval: limit ? interval : null, blocked_categories: blocked,
    });
    onSaved();
  });

  return (
    <div className="space-y-4 rounded-xl border border-line p-4">
      <Field label="Nickname"><Input value={nickname} maxLength={40} onChange={(e) => setNickname(e.target.value)} /></Field>
      <div className="grid grid-cols-2 gap-3">
        <Field label="Spend limit" hint="Leave empty for no limit"><MoneyInput value={limit} onChange={setLimit} placeholder="No limit" /></Field>
        <Field label="Resets">
          <Select value={interval} disabled={!limit} onChange={(e) => setInterval_(e.target.value as Interval)}>
            {INTERVALS.map((i) => <option key={i.value} value={i.value}>{i.label}</option>)}
          </Select>
        </Field>
      </div>
      <CategoryPicker all={cats.data ?? []} value={blocked} onChange={setBlocked} />
      <ErrorNote error={error} />
      <div className="flex justify-end"><Button variant="primary" disabled={busy} onClick={save}>{busy ? 'Saving…' : 'Save controls'}</Button></div>
    </div>
  );
}
