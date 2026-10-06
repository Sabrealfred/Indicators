'use client';

import { useEffect, useState, type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from 'react';
import { money, signedMoney, titleCase } from '@/lib/format';

export function cx(...c: (string | false | null | undefined)[]) {
  return c.filter(Boolean).join(' ');
}

// ---------- Layout ----------

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
        {subtitle && <p className="mt-1 text-muted">{subtitle}</p>}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  );
}

export function Card({ children, className, title, actions, padded = true }: { children: ReactNode; className?: string; title?: ReactNode; actions?: ReactNode; padded?: boolean }) {
  return (
    <section className={cx('rounded-xl border border-line bg-surface shadow-[0_1px_2px_rgba(0,0,0,0.03)]', className)}>
      {(title || actions) && (
        <header className="flex items-center justify-between gap-3 border-b border-line px-5 py-3.5">
          <h2 className="font-semibold">{title}</h2>
          {actions && <div className="flex gap-2">{actions}</div>}
        </header>
      )}
      <div className={padded ? 'p-5' : ''}>{children}</div>
    </section>
  );
}

export function Stat({ label, value, hint, tone }: { label: string; value: ReactNode; hint?: ReactNode; tone?: 'pos' | 'neg' }) {
  return (
    <div className="rounded-xl border border-line bg-surface p-5">
      <div className="text-[13px] text-muted">{label}</div>
      <div className={cx('mt-1.5 text-2xl font-semibold tabular tracking-tight', tone === 'pos' && 'text-pos', tone === 'neg' && 'text-neg')}>{value}</div>
      {hint && <div className="mt-1 text-[13px] text-muted">{hint}</div>}
    </div>
  );
}

export function Tabs<T extends string>({ value, onChange, tabs }: { value: T; onChange: (v: T) => void; tabs: { value: T; label: ReactNode }[] }) {
  return (
    <div className="mb-4 flex gap-1 overflow-x-auto border-b border-line">
      {tabs.map((t) => (
        <button
          key={t.value}
          onClick={() => onChange(t.value)}
          className={cx('-mb-px whitespace-nowrap border-b-2 px-3 py-2 font-medium transition-colors', value === t.value ? 'border-brand-600 text-ink' : 'border-transparent text-muted hover:text-ink')}
        >
          {t.label}
        </button>
      ))}
    </div>
  );
}

// ---------- Controls ----------

type BtnVariant = 'primary' | 'secondary' | 'ghost' | 'danger';
export function Button({ variant = 'secondary', size = 'md', className, ...p }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: BtnVariant; size?: 'sm' | 'md' }) {
  return (
    <button
      {...p}
      className={cx(
        'inline-flex items-center justify-center gap-1.5 rounded-lg font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50',
        size === 'sm' ? 'h-8 px-2.5 text-[13px]' : 'h-9 px-3.5',
        variant === 'primary' && 'bg-brand-600 text-white hover:bg-brand-700',
        variant === 'secondary' && 'border border-line bg-surface hover:bg-surface-2',
        variant === 'ghost' && 'text-muted hover:bg-surface-2 hover:text-ink',
        variant === 'danger' && 'border border-line bg-surface text-neg hover:bg-surface-2',
        className,
      )}
    />
  );
}

export function Field({ label, hint, error, children }: { label: string; hint?: ReactNode; error?: string | null; children: ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1 block text-[13px] font-medium">{label}</span>
      {children}
      {hint && !error && <span className="mt-1 block text-[12px] text-muted">{hint}</span>}
      {error && <span className="mt-1 block text-[12px] text-neg">{error}</span>}
    </label>
  );
}

const inputCls = 'h-9 w-full rounded-lg border border-line bg-surface px-3 outline-none focus:border-brand-500 focus:ring-2 focus:ring-brand-500/20';
export const Input = ({ className, ...p }: InputHTMLAttributes<HTMLInputElement>) => <input {...p} className={cx(inputCls, className)} />;
export const Select = ({ className, ...p }: SelectHTMLAttributes<HTMLSelectElement>) => <select {...p} className={cx(inputCls, 'pr-8', className)} />;
export const Textarea = ({ className, ...p }: TextareaHTMLAttributes<HTMLTextAreaElement>) => <textarea {...p} className={cx(inputCls, 'h-auto min-h-20 py-2', className)} />;

/** Dollar input that reports integer cents (or null when empty/invalid). */
export function MoneyInput({ value, onChange, placeholder = '0.00', autoFocus }: { value: number | null; onChange: (cents: number | null) => void; placeholder?: string; autoFocus?: boolean }) {
  const [text, setText] = useState(value != null ? (value / 100).toFixed(2) : '');
  return (
    <div className="relative">
      <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted">$</span>
      <input
        autoFocus={autoFocus}
        inputMode="decimal"
        className={cx(inputCls, 'pl-7 tabular')}
        placeholder={placeholder}
        value={text}
        onChange={(e) => {
          setText(e.target.value);
          const clean = e.target.value.replace(/[,\s]/g, '');
          onChange(/^\d+(\.\d{0,2})?$/.test(clean) ? Math.round(Number(clean) * 100) : null);
        }}
      />
    </div>
  );
}

// ---------- Overlays ----------

export function Modal({ open, onClose, title, children, footer, wide }: { open: boolean; onClose: () => void; title: string; children: ReactNode; footer?: ReactNode; wide?: boolean }) {
  useEffect(() => {
    if (!open) return;
    const h = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [open, onClose]);
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/40 p-4 pt-[8vh]" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className={cx('w-full rounded-xl border border-line bg-surface shadow-xl', wide ? 'max-w-3xl' : 'max-w-lg')}>
        <div className="flex items-center justify-between border-b border-line px-5 py-3.5">
          <h3 className="font-semibold">{title}</h3>
          <button onClick={onClose} className="rounded p-1 text-muted hover:bg-surface-2" aria-label="Close">✕</button>
        </div>
        <div className="space-y-4 p-5">{children}</div>
        {footer && <div className="flex justify-end gap-2 border-t border-line px-5 py-3.5">{footer}</div>}
      </div>
    </div>
  );
}

export function Drawer({ open, onClose, title, children }: { open: boolean; onClose: () => void; title: ReactNode; children: ReactNode }) {
  useEffect(() => {
    if (!open) return;
    const h = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [open, onClose]);
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-40 bg-black/30" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <aside className="absolute right-0 top-0 h-full w-full max-w-md overflow-y-auto border-l border-line bg-surface shadow-2xl">
        <div className="sticky top-0 flex items-center justify-between border-b border-line bg-surface px-5 py-3.5">
          <div className="font-semibold">{title}</div>
          <button onClick={onClose} className="rounded p-1 text-muted hover:bg-surface-2" aria-label="Close">✕</button>
        </div>
        <div className="p-5">{children}</div>
      </aside>
    </div>
  );
}

// ---------- Data display ----------

const BADGE_TONES: Record<string, string> = {
  green: 'bg-emerald-500/10 text-emerald-700 dark:text-emerald-400',
  yellow: 'bg-amber-500/10 text-amber-700 dark:text-amber-400',
  red: 'bg-red-500/10 text-red-700 dark:text-red-400',
  blue: 'bg-brand-500/10 text-brand-700 dark:text-brand-500',
  gray: 'bg-surface-2 text-muted',
};

const STATUS_TONE: Record<string, keyof typeof BADGE_TONES> = {
  posted: 'gray', completed: 'green', paid: 'green', active: 'green', approved: 'blue', open: 'green', sent: 'blue',
  pending: 'yellow', processing: 'yellow', scheduled: 'blue', pending_approval: 'yellow', submitted: 'yellow', draft: 'gray',
  failed: 'red', declined: 'red', returned: 'red', rejected: 'red', overdue: 'red', canceled: 'gray', void: 'gray', reversed: 'gray',
  frozen: 'blue', disabled: 'gray', closed: 'gray', invited: 'yellow',
};

export function Badge({ children, tone = 'gray' }: { children: ReactNode; tone?: keyof typeof BADGE_TONES }) {
  return <span className={cx('inline-flex items-center rounded-full px-2 py-0.5 text-[12px] font-medium', BADGE_TONES[tone])}>{children}</span>;
}

export function StatusBadge({ status }: { status: string }) {
  return <Badge tone={STATUS_TONE[status] ?? 'gray'}>{titleCase(status)}</Badge>;
}

export function Money({ cents, signed, className }: { cents: number; signed?: boolean; className?: string }) {
  return <span className={cx('tabular', signed && cents > 0 && 'text-pos', className)}>{signed ? signedMoney(cents) : money(cents)}</span>;
}

export function Table({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div className={cx('overflow-x-auto', className)}>
      <table className="w-full border-collapse text-left">{children}</table>
    </div>
  );
}
export const Th = ({ children, className, right }: { children?: ReactNode; className?: string; right?: boolean }) => (
  <th className={cx('border-b border-line px-4 py-2.5 text-[12px] font-medium uppercase tracking-wide text-muted', right && 'text-right', className)}>{children}</th>
);
export const Td = ({ children, className, right, ...p }: { children?: ReactNode; className?: string; right?: boolean; colSpan?: number }) => (
  <td {...p} className={cx('border-b border-line px-4 py-3 align-middle', right && 'text-right tabular', className)}>{children}</td>
);
export const Tr = ({ children, onClick, className }: { children: ReactNode; onClick?: () => void; className?: string }) => (
  <tr onClick={onClick} className={cx(onClick && 'cursor-pointer hover:bg-surface-2', className)}>{children}</tr>
);

export function Empty({ title, hint, action }: { title: string; hint?: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center px-6 py-14 text-center">
      <div className="font-medium">{title}</div>
      {hint && <div className="mt-1 max-w-sm text-muted">{hint}</div>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

export function Loading({ label = 'Loading…' }: { label?: string }) {
  return <div className="px-6 py-14 text-center text-muted">{label}</div>;
}

export function ErrorNote({ error }: { error: string | null | undefined }) {
  if (!error) return null;
  return <div className="rounded-lg border border-red-500/30 bg-red-500/5 px-3 py-2 text-[13px] text-neg">{error}</div>;
}

export function KeyValue({ rows }: { rows: [ReactNode, ReactNode][] }) {
  return (
    <dl className="divide-y divide-line">
      {rows.map(([k, v], i) => (
        <div key={i} className="flex justify-between gap-4 py-2.5">
          <dt className="text-muted">{k}</dt>
          <dd className="text-right font-medium">{v}</dd>
        </div>
      ))}
    </dl>
  );
}

/** Wraps an async action with busy + error state for forms and buttons. */
export function useAction() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const run = async <T,>(fn: () => Promise<T>): Promise<T | undefined> => {
    setBusy(true);
    setError(null);
    try {
      return await fn();
    } catch (e) {
      setError((e as Error).message);
      return undefined;
    } finally {
      setBusy(false);
    }
  };
  return { busy, error, run, setError };
}
