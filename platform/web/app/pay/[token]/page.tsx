'use client';

import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import { api } from '@/lib/api';
import { date, money } from '@/lib/format';
import { Button, ErrorNote, StatusBadge, cx } from '@/components/ui';

interface PublicInvoice {
  number: string;
  issue_date: string;
  due_date: string;
  status: 'draft' | 'sent' | 'paid' | 'void';
  memo: string | null;
  total: number;
  amount_paid: number;
  customer_name: string;
  overdue: boolean;
  lines: { id: number; description: string; quantity: number; unit_price: number; amount: number }[];
  from: { name: string; legal_name: string | null; address: string | null };
  pay_to: { routing_number: string; account_number_last4: string };
}

type Method = 'ach' | 'card';

export default function HostedInvoicePage() {
  const { token } = useParams<{ token: string }>();
  const [inv, setInv] = useState<PublicInvoice | null>(null);
  const [loadErr, setLoadErr] = useState<string | null>(null);
  const [method, setMethod] = useState<Method>('ach');
  const [busy, setBusy] = useState(false);
  const [payErr, setPayErr] = useState<string | null>(null);
  const [paidNow, setPaidNow] = useState<{ amount: number; method: Method } | null>(null);

  const load = useCallback(() => {
    api<PublicInvoice>(`/public/invoices/${encodeURIComponent(token)}`)
      .then((d) => { setInv(d); setLoadErr(null); })
      .catch((e: Error) => setLoadErr(e.message));
  }, [token]);
  useEffect(() => { if (token) load(); }, [token, load]);

  useEffect(() => {
    if (inv) document.title = `Invoice ${inv.number} from ${inv.from.name}`;
  }, [inv]);

  const balance = inv ? inv.total - inv.amount_paid : 0;

  const pay = async () => {
    if (!inv) return;
    setBusy(true);
    setPayErr(null);
    try {
      const updated = await api<PublicInvoice>(`/public/invoices/${encodeURIComponent(token)}/pay`, { method: 'POST', body: { method } });
      setPaidNow({ amount: balance, method });
      setInv(updated);
    } catch (e) {
      setPayErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  if (loadErr) {
    return (
      <Shell>
        <div className="rounded-xl border border-line bg-surface p-10 text-center">
          <div className="text-lg font-semibold">Invoice not found</div>
          <p className="mt-1 text-muted">This payment link is invalid or has expired. Please contact the sender for a new link.</p>
        </div>
      </Shell>
    );
  }
  if (!inv) return <Shell><div className="py-20 text-center text-muted">Loading invoice…</div></Shell>;

  const statusKey = inv.status === 'sent' && inv.overdue ? 'overdue' : inv.status === 'sent' ? 'open' : inv.status;

  return (
    <Shell>
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-[1fr_340px]">
        {/* Invoice document */}
        <article className="rounded-xl border border-line bg-surface p-5 shadow-[0_1px_2px_rgba(0,0,0,0.03)] sm:p-8">
          <header className="flex flex-wrap items-start justify-between gap-4">
            <div>
              <div className="grid h-10 w-10 place-items-center rounded-lg bg-brand-600 text-lg font-bold text-white">{inv.from.name.charAt(0)}</div>
              <div className="mt-3 font-semibold">{inv.from.legal_name || inv.from.name}</div>
              {inv.from.address && <div className="max-w-56 whitespace-pre-line text-[13px] text-muted">{inv.from.address}</div>}
            </div>
            <div className="text-right">
              <div className="text-2xl font-semibold tracking-tight">Invoice</div>
              <div className="text-muted">{inv.number}</div>
              <div className="mt-2"><StatusBadge status={statusKey} /></div>
            </div>
          </header>

          <div className="mt-8 grid gap-6 border-y border-line py-5 sm:grid-cols-3">
            <div>
              <div className="text-[12px] font-medium uppercase tracking-wide text-muted">Bill to</div>
              <div className="mt-1 font-medium">{inv.customer_name}</div>
            </div>
            <div>
              <div className="text-[12px] font-medium uppercase tracking-wide text-muted">Issued</div>
              <div className="mt-1">{date(inv.issue_date)}</div>
            </div>
            <div>
              <div className="text-[12px] font-medium uppercase tracking-wide text-muted">Due</div>
              <div className={cx('mt-1', inv.status === 'sent' && inv.overdue && 'font-medium text-neg')}>
                {date(inv.due_date)}{inv.status === 'sent' && inv.overdue && ' · Overdue'}
              </div>
            </div>
          </div>

          <div className="mt-6 overflow-x-auto">
            <table className="w-full text-left">
              <thead>
                <tr className="text-[12px] uppercase tracking-wide text-muted">
                  <th className="pb-2 font-medium">Description</th>
                  <th className="pb-2 text-right font-medium">Qty</th>
                  <th className="hidden pb-2 text-right font-medium sm:table-cell">Unit price</th>
                  <th className="pb-2 text-right font-medium">Amount</th>
                </tr>
              </thead>
              <tbody>
                {inv.lines.map((l) => (
                  <tr key={l.id} className="border-t border-line">
                    <td className="py-3 pr-3">
                      {l.description}
                      <div className="text-[12px] text-muted sm:hidden">{money(l.unit_price)} each</div>
                    </td>
                    <td className="py-3 text-right tabular">{l.quantity}</td>
                    <td className="hidden py-3 text-right tabular sm:table-cell">{money(l.unit_price)}</td>
                    <td className="py-3 text-right tabular">{money(l.amount)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="mt-4 flex justify-end">
            <dl className="w-full max-w-xs space-y-1.5 border-t border-line pt-4">
              <div className="flex justify-between"><dt className="text-muted">Total</dt><dd className="tabular">{money(inv.total)}</dd></div>
              <div className="flex justify-between"><dt className="text-muted">Amount paid</dt><dd className="tabular">{inv.amount_paid ? `−${money(inv.amount_paid)}` : money(0)}</dd></div>
              <div className="flex justify-between border-t border-line pt-2 text-lg font-semibold"><dt>Balance due</dt><dd className="tabular">{money(balance)}</dd></div>
            </dl>
          </div>

          {inv.memo && <p className="mt-8 border-t border-line pt-4 text-[13px] text-muted">{inv.memo}</p>}
        </article>

        {/* Payment panel */}
        <aside className="h-fit rounded-xl border border-line bg-surface p-5 shadow-[0_1px_2px_rgba(0,0,0,0.03)] lg:sticky lg:top-6">
          {inv.status === 'paid' ? (
            <div className="py-4 text-center">
              <div className="mx-auto grid h-12 w-12 place-items-center rounded-full bg-emerald-500/10 text-2xl text-pos">✓</div>
              <div className="mt-3 text-lg font-semibold">{paidNow ? 'Payment successful' : 'Paid in full'}</div>
              <p className="mt-1 text-[13px] text-muted">
                {paidNow
                  ? `Your ${paidNow.method === 'card' ? 'card' : 'bank transfer'} payment of ${money(paidNow.amount)} to ${inv.from.name} was received. Thank you!`
                  : `This invoice has been paid. Thank you for your business.`}
              </p>
            </div>
          ) : inv.status === 'void' ? (
            <div className="py-4 text-center">
              <div className="text-lg font-semibold">Invoice voided</div>
              <p className="mt-1 text-[13px] text-muted">This invoice was canceled by {inv.from.name} and no payment is due.</p>
            </div>
          ) : inv.status === 'draft' ? (
            <div className="py-4 text-center text-muted">This invoice hasn’t been issued yet.</div>
          ) : (
            <>
              <div className="text-[13px] text-muted">Amount due</div>
              <div className="text-3xl font-semibold tracking-tight tabular">{money(balance)}</div>
              <div className={cx('text-[13px]', inv.overdue ? 'text-neg' : 'text-muted')}>{inv.overdue ? 'Overdue — was due' : 'Due'} {date(inv.due_date)}</div>

              <div className="mt-5 grid grid-cols-2 rounded-lg bg-surface-2 p-1" role="tablist">
                {(['ach', 'card'] as Method[]).map((m) => (
                  <button
                    key={m}
                    type="button"
                    role="tab"
                    aria-selected={method === m}
                    onClick={() => setMethod(m)}
                    className={cx('rounded-md py-1.5 text-[13px] font-medium', method === m ? 'bg-surface shadow-sm' : 'text-muted hover:text-ink')}
                  >
                    {m === 'ach' ? 'Bank transfer (ACH)' : 'Card'}
                  </button>
                ))}
              </div>

              <div className="mt-4 rounded-lg border border-line p-3 text-[13px]">
                {method === 'ach' ? (
                  <>
                    <div className="font-medium">Pay from your bank account</div>
                    <div className="mt-1 text-muted">No fees. Funds typically arrive in 1 business day.</div>
                    <div className="mt-2 space-y-0.5 text-[12px] text-muted">
                      <div>Routing: <span className="tabular text-ink">{inv.pay_to.routing_number}</span></div>
                      <div>Account: <span className="tabular text-ink">••••{inv.pay_to.account_number_last4}</span></div>
                    </div>
                  </>
                ) : (
                  <>
                    <div className="font-medium">Pay with a credit or debit card</div>
                    <div className="mt-1 text-muted">Processed instantly. Demo mode — no card details are collected.</div>
                  </>
                )}
              </div>

              <div className="mt-4"><ErrorNote error={payErr} /></div>
              <Button variant="primary" className="mt-2 h-11 w-full text-[15px]" disabled={busy || balance <= 0} onClick={pay}>
                {busy ? 'Processing…' : `Pay ${money(balance)}`}
              </Button>
              <p className="mt-3 text-center text-[11px] text-muted">Payments are simulated in this demo environment.</p>
            </>
          )}
        </aside>
      </div>
    </Shell>
  );
}

function Shell({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-screen bg-canvas">
      <main className="mx-auto max-w-5xl px-4 py-8 sm:px-6 sm:py-12">{children}</main>
      <footer className="pb-10 text-center text-[12px] text-muted">
        Secure invoice payments powered by <span className="font-semibold text-ink">LedgerBank</span>
      </footer>
    </div>
  );
}
