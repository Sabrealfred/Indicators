'use client';

import { useEffect, useMemo, useState } from 'react';
import { api, useApi } from '@/lib/api';
import { useSession } from '@/lib/session';
import { date, money, RAIL_LABELS, todayIso } from '@/lib/format';
import type { Account, Payment, Recipient } from '@/lib/types';
import { Badge, Button, cx, ErrorNote, Field, Input, KeyValue, Loading, Modal, MoneyInput, Select, StatusBadge, Textarea, useAction } from '@/components/ui';

export const RAIL_ETA: Record<string, string> = {
  book: 'Instant',
  wire: 'About 15 minutes',
  same_day_ach: 'Same day (about 4 hours)',
  ach: '1 business day',
  intl_wire: '1 business day',
  check: '5 business days by mail',
};

const RAIL_FEE_HINT: Record<string, string> = {
  book: 'Between your LedgerBank accounts',
  ach: 'Free · standard bank transfer',
  same_day_ach: 'Free · arrives today if sent before cutoff',
  wire: 'Free · fastest domestic option',
  intl_wire: 'Free · sent via SWIFT',
  check: 'Free · printed and mailed',
};

/** Rails a recipient supports, in display order. */
export function railsFor(r: Recipient | undefined | null): string[] {
  if (!r) return [];
  const out: string[] = [];
  if (r.ach_account) out.push('ach', 'same_day_ach');
  if (r.wire_account) out.push('wire');
  if (r.iban) out.push('intl_wire');
  if (r.address) out.push('check');
  return out;
}

type Step = 'details' | 'review' | 'done';

export function SendMoneyModal({ open, onClose, onSent }: { open: boolean; onClose: () => void; onSent: () => void }) {
  const { user, organization } = useSession();
  const accounts = useApi<Account[]>(open ? '/accounts' : null);
  const recipients = useApi<Recipient[]>(open ? '/recipients' : null);

  const [step, setStep] = useState<Step>('details');
  const [fromId, setFromId] = useState('');
  const [mode, setMode] = useState<'recipient' | 'internal'>('recipient');
  const [recipientId, setRecipientId] = useState('');
  const [toAccountId, setToAccountId] = useState('');
  const [search, setSearch] = useState('');
  const [rail, setRail] = useState('');
  const [amount, setAmount] = useState<number | null>(null);
  const [memo, setMemo] = useState('');
  const [scheduled, setScheduled] = useState('');
  const [idemKey, setIdemKey] = useState('');
  const [result, setResult] = useState<Payment | null>(null);
  const [amountKey, setAmountKey] = useState(0);
  const act = useAction();

  const reset = () => {
    setStep('details');
    setFromId('');
    setMode('recipient');
    setRecipientId('');
    setToAccountId('');
    setSearch('');
    setRail('');
    setAmount(null);
    setAmountKey((k) => k + 1);
    setMemo('');
    setScheduled('');
    setResult(null);
    setIdemKey(crypto.randomUUID());
    act.setError(null);
  };

  useEffect(() => {
    if (open) reset();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const deposit = useMemo(() => (accounts.data ?? []).filter((a) => a.type !== 'credit' && a.status === 'open'), [accounts.data]);
  useEffect(() => {
    if (!fromId && deposit.length) setFromId(deposit[0].id);
  }, [deposit, fromId]);

  const from = deposit.find((a) => a.id === fromId);
  const recipient = recipients.data?.find((r) => r.id === recipientId);
  const toAccount = (accounts.data ?? []).find((a) => a.id === toAccountId);
  const rails = mode === 'internal' ? ['book'] : railsFor(recipient);
  const effectiveRail = rails.includes(rail) ? rail : rails[0] ?? '';

  const filtered = useMemo(() => {
    const s = search.trim().toLowerCase();
    return (recipients.data ?? []).filter((r) => railsFor(r).length && (!s || r.name.toLowerCase().includes(s)));
  }, [recipients.data, search]);

  const overAvailable = !!from && amount != null && amount > from.balance.available;
  const memoTooLong = memo.length > 140;
  const destOk = mode === 'internal' ? !!toAccountId && toAccountId !== fromId : !!recipient;
  const canReview = !!from && destOk && !!effectiveRail && amount != null && amount > 0 && !memoTooLong;
  const needsApproval = effectiveRail !== 'book' && amount != null && (amount >= organization.approval_threshold || user.role !== 'admin');
  const toName = mode === 'internal' ? toAccount?.name : recipient?.name;

  const submit = async () => {
    if (!from || amount == null) return;
    const body: Record<string, unknown> = { account_id: from.id, rail: effectiveRail, amount, memo: memo.trim() || null, scheduled_for: scheduled || null };
    if (mode === 'internal') body.to_account_id = toAccountId;
    else body.counterparty_id = recipientId;
    const p = await act.run(() => api<Payment>('/payments', { method: 'POST', body, headers: { 'Idempotency-Key': idemKey } }));
    if (p) {
      setResult(p);
      setStep('done');
      onSent();
    }
  };

  const title = step === 'done' ? 'Payment created' : step === 'review' ? 'Review payment' : 'Send money';

  const footer =
    step === 'details' ? (
      <>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="primary" disabled={!canReview} onClick={() => setStep('review')}>Review</Button>
      </>
    ) : step === 'review' ? (
      <>
        <Button onClick={() => setStep('details')} disabled={act.busy}>Back</Button>
        <Button variant="primary" disabled={act.busy} onClick={submit}>
          {act.busy ? 'Sending…' : needsApproval ? 'Submit for approval' : `Send ${money(amount)}`}
        </Button>
      </>
    ) : (
      <>
        <Button onClick={reset}>Send another</Button>
        <Button variant="primary" onClick={onClose}>Done</Button>
      </>
    );

  return (
    <Modal open={open} onClose={onClose} title={title} footer={footer} wide={step === 'details'}>
      <StepDots step={step} />
      {step === 'details' && (
        !accounts.data || !recipients.data ? (
          <>
            <ErrorNote error={accounts.error ?? recipients.error} />
            {!accounts.error && !recipients.error && <Loading />}
          </>
        ) : (
          <div className="space-y-4">
            <Field label="From">
              <Select value={fromId} onChange={(e) => setFromId(e.target.value)}>
                {!deposit.length && <option value="">No open deposit accounts</option>}
                {deposit.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.name} ••{a.account_number.slice(-4)} — {money(a.balance.available)} available
                  </option>
                ))}
              </Select>
            </Field>

            <div>
              <div className="mb-1 text-[13px] font-medium">To</div>
              <div className="mb-2 inline-flex rounded-lg border border-line p-0.5">
                {(['recipient', 'internal'] as const).map((m) => (
                  <button
                    key={m}
                    onClick={() => setMode(m)}
                    className={cx('rounded-md px-3 py-1 text-[13px] font-medium', mode === m ? 'bg-surface-2 text-ink' : 'text-muted hover:text-ink')}
                  >
                    {m === 'recipient' ? 'Recipient' : 'My accounts'}
                  </button>
                ))}
              </div>

              {mode === 'recipient' ? (
                <div className="rounded-lg border border-line">
                  <div className="border-b border-line p-2">
                    <Input type="search" placeholder="Search recipients" value={search} onChange={(e) => setSearch(e.target.value)} />
                  </div>
                  <ul className="max-h-52 overflow-y-auto">
                    {!filtered.length && (
                      <li className="px-3 py-4 text-center text-[13px] text-muted">
                        No recipients with payment details. <a href="/recipients" className="text-brand-600">Add one →</a>
                      </li>
                    )}
                    {filtered.map((r) => (
                      <li key={r.id}>
                        <button
                          onClick={() => setRecipientId(r.id)}
                          className={cx('flex w-full items-center justify-between gap-2 px-3 py-2 text-left hover:bg-surface-2', r.id === recipientId && 'bg-brand-500/5')}
                        >
                          <span className="flex items-center gap-2">
                            <span className={cx('h-3.5 w-3.5 shrink-0 rounded-full border', r.id === recipientId ? 'border-4 border-brand-600' : 'border-line')} />
                            <span className="font-medium">{r.name}</span>
                          </span>
                          <span className="flex flex-wrap justify-end gap-1">
                            {railsFor(r).filter((x) => x !== 'same_day_ach').map((x) => (
                              <Badge key={x}>{x === 'ach' ? 'ACH' : x === 'wire' ? 'Wire' : x === 'intl_wire' ? 'Intl' : 'Check'}</Badge>
                            ))}
                          </span>
                        </button>
                      </li>
                    ))}
                  </ul>
                </div>
              ) : (
                <Select value={toAccountId} onChange={(e) => setToAccountId(e.target.value)}>
                  <option value="">Select an account…</option>
                  {(accounts.data ?? [])
                    .filter((a) => a.id !== fromId && a.status === 'open')
                    .map((a) => (
                      <option key={a.id} value={a.id}>{a.name} ••{a.account_number.slice(-4)}{a.type === 'credit' ? ' (pay down credit)' : ''}</option>
                    ))}
                </Select>
              )}
            </div>

            {rails.length > 0 && (
              <div>
                <div className="mb-1 text-[13px] font-medium">Payment method</div>
                <div className="grid gap-2 sm:grid-cols-2">
                  {rails.map((r) => (
                    <button
                      key={r}
                      onClick={() => setRail(r)}
                      className={cx(
                        'rounded-lg border px-3 py-2 text-left transition-colors',
                        effectiveRail === r ? 'border-brand-600 bg-brand-500/5 ring-1 ring-brand-600' : 'border-line hover:bg-surface-2',
                      )}
                    >
                      <div className="flex items-center justify-between gap-2">
                        <span className="font-medium">{RAIL_LABELS[r]}</span>
                        <span className="text-[12px] text-muted">{RAIL_ETA[r]}</span>
                      </div>
                      <div className="text-[12px] text-muted">{RAIL_FEE_HINT[r]}</div>
                    </button>
                  ))}
                </div>
              </div>
            )}

            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Amount" error={overAvailable ? `Exceeds available balance of ${money(from?.balance.available)}` : null}>
                <MoneyInput key={amountKey} value={amount} onChange={setAmount} />
              </Field>
              <Field label="Send on (optional)" hint="Leave blank to send now">
                <Input type="date" min={todayIso()} value={scheduled} onChange={(e) => setScheduled(e.target.value)} />
              </Field>
            </div>
            <Field label="Memo (optional)" error={memoTooLong ? 'Memo must be 140 characters or fewer' : null} hint={`${memo.length}/140 · visible to the recipient`}>
              <Textarea value={memo} onChange={(e) => setMemo(e.target.value)} className="min-h-14" placeholder="Invoice #1042" />
            </Field>
          </div>
        )
      )}

      {step === 'review' && from && amount != null && (
        <>
          <div className="text-center">
            <div className="text-[13px] text-muted">You&apos;re sending</div>
            <div className="text-3xl font-semibold tabular tracking-tight">{money(amount)}</div>
            <div className="mt-1 text-muted">to <span className="font-medium text-ink">{toName}</span></div>
          </div>
          <KeyValue
            rows={[
              ['From', `${from.name} ••${from.account_number.slice(-4)}`],
              ['To', mode === 'internal' ? `${toAccount?.name} ••${toAccount?.account_number.slice(-4)}` : recipientDest(recipient!, effectiveRail)],
              ['Method', RAIL_LABELS[effectiveRail]],
              ['Send date', scheduled ? date(scheduled) : 'Today'],
              ['Estimated arrival', scheduled ? `${RAIL_ETA[effectiveRail]} after ${date(scheduled)}` : RAIL_ETA[effectiveRail]],
              ...(memo.trim() ? ([['Memo', memo.trim()]] as [string, string][]) : []),
            ]}
          />
          {needsApproval && (
            <div className="rounded-lg border border-amber-500/30 bg-amber-500/5 px-3 py-2 text-[13px] text-amber-800 dark:text-amber-300">
              {user.role !== 'admin' ? 'Payments you initiate need an admin’s approval before they’re sent.' : `Payments of ${money(organization.approval_threshold)} or more need approval before they’re sent.`}
            </div>
          )}
          {overAvailable && <ErrorNote error={`This exceeds the available balance (${money(from.balance.available)}). The payment may fail for insufficient funds.`} />}
          <ErrorNote error={act.error} />
        </>
      )}

      {step === 'done' && result && <PaymentResult p={result} toName={toName ?? ''} />}
    </Modal>
  );
}

function recipientDest(r: Recipient, rail: string) {
  if (rail === 'intl_wire') return `${r.name} · IBAN ••${r.iban?.slice(-4)}`;
  if (rail === 'wire') return `${r.name} · ••${r.wire_account?.slice(-4)}`;
  if (rail === 'check') return `${r.name} · by mail`;
  return `${r.name} · ••${r.ach_account?.slice(-4)}`;
}

function StepDots({ step }: { step: Step }) {
  const steps: Step[] = ['details', 'review', 'done'];
  const idx = steps.indexOf(step);
  return (
    <div className="flex items-center gap-2 text-[12px] text-muted">
      {['Details', 'Review', 'Done'].map((s, i) => (
        <span key={s} className="flex items-center gap-2">
          <span className={cx('grid h-5 w-5 place-items-center rounded-full text-[11px] font-semibold', i <= idx ? 'bg-brand-600 text-white' : 'bg-surface-2')}>{i + 1}</span>
          <span className={i === idx ? 'font-medium text-ink' : ''}>{s}</span>
          {i < 2 && <span className="w-6 border-t border-line" />}
        </span>
      ))}
    </div>
  );
}

function PaymentResult({ p, toName }: { p: Payment; toName: string }) {
  const headline: Record<string, string> = {
    pending_approval: 'Needs approval',
    scheduled: p.scheduled_for ? `Scheduled for ${date(p.scheduled_for)}` : 'Scheduled',
    processing: 'On its way',
    completed: 'Sent',
    failed: 'Payment failed',
  };
  const detail: Record<string, string> = {
    pending_approval: 'An admin needs to approve this payment before it is sent. It’s waiting in the Approvals tab.',
    scheduled: 'The payment will be sent automatically on the scheduled date.',
    processing: `Funds are on hold and the payment will settle in ${RAIL_ETA[p.rail]?.toLowerCase()}.`,
    completed: 'The transfer has completed.',
    failed: p.failure_reason ?? 'The payment could not be sent.',
  };
  return (
    <div className="py-2 text-center">
      <div className={cx('mx-auto grid h-12 w-12 place-items-center rounded-full text-xl', p.status === 'failed' ? 'bg-red-500/10 text-neg' : p.status === 'pending_approval' ? 'bg-amber-500/10 text-amber-600' : 'bg-emerald-500/10 text-emerald-600')}>
        {p.status === 'failed' ? '!' : p.status === 'pending_approval' ? '⏳' : '✓'}
      </div>
      <div className="mt-3 text-lg font-semibold">{headline[p.status] ?? p.status}</div>
      <div className="mt-1 text-muted">
        {money(p.amount)} to {toName} via {RAIL_LABELS[p.rail]}
      </div>
      <div className="mt-2"><StatusBadge status={p.status} /></div>
      <p className="mx-auto mt-3 max-w-sm text-[13px] text-muted">{detail[p.status] ?? ''}</p>
    </div>
  );
}
