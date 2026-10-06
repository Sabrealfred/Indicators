'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { api, setToken } from '@/lib/api';
import { Button, ErrorNote, Field, Input } from '@/components/ui';

const DEMO = [
  ['demo@ledgerbank.dev', 'Admin — Ana Rivera'],
  ['marcus@acmerobotics.dev', 'Admin — Marcus Lee (second approver)'],
  ['priya@acmerobotics.dev', 'Bookkeeper — Priya Shah'],
  ['diego@acmerobotics.dev', 'Employee — Diego Santos'],
];

export default function Login() {
  const router = useRouter();
  const [email, setEmail] = useState('demo@ledgerbank.dev');
  const [password, setPassword] = useState('demo1234');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const { token } = await api<{ token: string }>('/auth/login', { method: 'POST', body: { email, password } });
      setToken(token);
      router.replace('/');
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="grid min-h-screen place-items-center px-4">
      <div className="w-full max-w-sm">
        <div className="mb-8 flex items-center gap-2">
          <div className="grid h-8 w-8 place-items-center rounded-lg bg-brand-600 font-bold text-white">L</div>
          <span className="text-lg font-semibold">LedgerBank</span>
        </div>
        <h1 className="text-2xl font-semibold tracking-tight">Log in</h1>
        <p className="mt-1 text-muted">Demo environment — all money is simulated.</p>
        <form onSubmit={submit} className="mt-6 space-y-4">
          <Field label="Email"><Input type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="email" /></Field>
          <Field label="Password"><Input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" /></Field>
          <ErrorNote error={error} />
          <Button variant="primary" className="w-full" disabled={busy}>{busy ? 'Logging in…' : 'Log in'}</Button>
        </form>
        <div className="mt-8 rounded-xl border border-line bg-surface p-4 text-[13px]">
          <div className="mb-2 font-medium">Demo users (password demo1234)</div>
          {DEMO.map(([e, label]) => (
            <button key={e} onClick={() => setEmail(e)} className="block w-full rounded px-1 py-1 text-left text-muted hover:bg-surface-2 hover:text-ink">
              {label}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
