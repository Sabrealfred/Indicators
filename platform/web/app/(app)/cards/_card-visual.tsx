'use client';

import type { Card } from '@/lib/types';
import { cx } from '@/components/ui';

const GRADIENTS = [
  'from-slate-900 via-slate-800 to-indigo-950',
  'from-zinc-900 via-neutral-800 to-stone-900',
  'from-indigo-950 via-slate-900 to-slate-800',
  'from-gray-900 via-slate-900 to-emerald-950',
];

function hash(s: string) {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
  return Math.abs(h);
}

export const expLabel = (m: number, y: number) => `${String(m).padStart(2, '0')}/${String(y).slice(-2)}`;

export function CardVisual({ card, holder, onClick, className }: { card: Card; holder?: string; onClick?: () => void; className?: string }) {
  const g = GRADIENTS[hash(card.id) % GRADIENTS.length];
  const dim = card.status !== 'active';
  return (
    <button
      type="button"
      onClick={onClick}
      className={cx(
        'group relative block aspect-[1.586] w-full overflow-hidden rounded-2xl bg-gradient-to-br p-4 text-left text-white shadow-md ring-1 ring-black/10 transition-transform sm:p-5',
        onClick && 'hover:-translate-y-0.5 hover:shadow-lg',
        g,
        className,
      )}
    >
      <div className="pointer-events-none absolute -right-12 -top-16 h-48 w-48 rounded-full bg-white/5" />
      <div className="pointer-events-none absolute -bottom-20 -left-10 h-48 w-48 rounded-full bg-indigo-400/10" />
      <div className={cx('relative flex h-full flex-col justify-between', dim && 'opacity-60')}>
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0">
            <div className="truncate font-semibold">{card.nickname}</div>
            {holder && <div className="truncate text-[12px] text-white/60">{holder}</div>}
          </div>
          <span className="shrink-0 rounded bg-white/10 px-1.5 py-0.5 text-[10px] font-semibold tracking-widest">{card.form.toUpperCase()}</span>
        </div>
        <div>
          <div className="font-mono text-[15px] tracking-[0.2em] sm:text-base">•••• {card.last4}</div>
          <div className="mt-1.5 flex items-end justify-between text-[12px] text-white/70">
            <span>EXP {expLabel(card.exp_month, card.exp_year)}</span>
            <span className="text-[13px] font-semibold italic tracking-wide text-white/90">{card.network === 'mastercard' ? 'mastercard' : card.network.toUpperCase()}</span>
          </div>
        </div>
      </div>
      {card.status === 'frozen' && (
        <div className="absolute inset-0 grid place-items-center bg-sky-200/10 backdrop-blur-[1.5px]">
          <span className="rounded-full bg-white/90 px-3 py-1 text-[12px] font-semibold text-sky-800">❄ Frozen</span>
        </div>
      )}
      {card.status === 'canceled' && (
        <div className="absolute inset-0 grid place-items-center bg-black/40">
          <span className="rounded-full bg-white/90 px-3 py-1 text-[12px] font-semibold text-gray-700">Canceled</span>
        </div>
      )}
    </button>
  );
}

export function ProgressBar({ value, max, className }: { value: number; max: number; className?: string }) {
  const pct = max > 0 ? Math.min(100, (value / max) * 100) : 0;
  return (
    <div className={cx('h-2 overflow-hidden rounded-full bg-surface-2', className)}>
      <div className={cx('h-2 rounded-full', pct >= 90 ? 'bg-red-500' : pct >= 70 ? 'bg-amber-500' : 'bg-brand-500')} style={{ width: `${pct}%` }} />
    </div>
  );
}
