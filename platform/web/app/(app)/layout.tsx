'use client';

import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { api, getToken, setToken, useApi } from '@/lib/api';
import { SessionContext } from '@/lib/session';
import type { Me, Role } from '@/lib/types';
import { cx } from '@/components/ui';

type NavItem = { href: string; label: string; roles?: Role[] };
const NAV: { section?: string; items: NavItem[] }[] = [
  { items: [{ href: '/', label: 'Home' }, { href: '/transactions', label: 'Transactions' }, { href: '/accounts', label: 'Accounts' }] },
  {
    section: 'Money movement',
    items: [
      { href: '/payments', label: 'Payments', roles: ['admin', 'bookkeeper'] },
      { href: '/recipients', label: 'Recipients', roles: ['admin', 'bookkeeper'] },
      { href: '/cards', label: 'Cards' },
      { href: '/credit', label: 'IO Credit', roles: ['admin', 'bookkeeper'] },
      { href: '/treasury', label: 'Treasury', roles: ['admin', 'bookkeeper'] },
    ],
  },
  {
    section: 'Workflows',
    items: [
      { href: '/bills', label: 'Bill pay', roles: ['admin', 'bookkeeper'] },
      { href: '/invoices', label: 'Invoicing', roles: ['admin', 'bookkeeper'] },
      { href: '/reimbursements', label: 'Reimbursements' },
    ],
  },
  {
    section: 'Books',
    items: [
      { href: '/books', label: 'Overview', roles: ['admin', 'bookkeeper'] },
      { href: '/books/reports', label: 'Reports', roles: ['admin', 'bookkeeper'] },
      { href: '/books/ledger', label: 'Chart of accounts', roles: ['admin', 'bookkeeper'] },
      { href: '/books/journal', label: 'Journal entries', roles: ['admin', 'bookkeeper'] },
      { href: '/books/reconcile', label: 'Reconciliation', roles: ['admin', 'bookkeeper'] },
      { href: '/books/rules', label: 'Rules', roles: ['admin', 'bookkeeper'] },
    ],
  },
  {
    section: 'Company',
    items: [
      { href: '/team', label: 'Team' },
      { href: '/settings', label: 'Settings', roles: ['admin'] },
      { href: '/simulator', label: 'Simulator', roles: ['admin'] },
    ],
  },
];

export default function AppLayout({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const path = usePathname();
  const [ready, setReady] = useState(false);
  const [menu, setMenu] = useState(false);
  useEffect(() => {
    if (!getToken()) router.replace('/login');
    else setReady(true);
  }, [router]);
  const me = useApi<Me>(ready ? '/me' : null);
  useEffect(() => setMenu(false), [path]);

  if (!ready || !me.data) return <div className="p-10 text-muted">{me.error ?? 'Loading…'}</div>;
  const role = me.data.user.role;
  // The nav item that owns this path (longest prefix) decides who may open it, so a typed URL
  // shows a clear message instead of a page full of refused API calls.
  const owner = NAV.flatMap((g) => g.items).filter((it) => (it.href === '/' ? path === '/' : path === it.href || path.startsWith(it.href + '/'))).sort((a, b) => b.href.length - a.href.length)[0];
  const allowed = !owner?.roles || owner.roles.includes(role);
  const active = (href: string) => (href === '/' ? path === '/' : href === '/books' ? path === '/books' : path.startsWith(href));

  const logout = async () => {
    await api('/auth/logout', { method: 'POST', body: {} }).catch(() => {});
    setToken(null);
    router.replace('/login');
  };

  const sidebar = (
    <nav className="flex h-full flex-col">
      <div className="flex items-center gap-2 px-5 py-5">
        <div className="grid h-7 w-7 place-items-center rounded-lg bg-brand-600 text-[13px] font-bold text-white">L</div>
        <div className="leading-tight">
          <div className="font-semibold">{me.data.organization.name}</div>
          <div className="text-[12px] text-muted">LedgerBank</div>
        </div>
      </div>
      <div className="flex-1 space-y-5 overflow-y-auto px-3 pb-4">
        {NAV.map((g, i) => {
          const items = g.items.filter((it) => !it.roles || it.roles.includes(role));
          if (!items.length) return null;
          return (
            <div key={i}>
              {g.section && <div className="px-2 pb-1 text-[11px] font-semibold uppercase tracking-wider text-muted">{g.section}</div>}
              {items.map((it) => (
                <Link key={it.href} href={it.href} className={cx('block rounded-lg px-2 py-1.5 font-medium', active(it.href) ? 'bg-surface-2 text-ink' : 'text-muted hover:bg-surface-2 hover:text-ink')}>
                  {it.label}
                </Link>
              ))}
            </div>
          );
        })}
      </div>
      <div className="border-t border-line px-5 py-4">
        <div className="font-medium">{me.data.user.name}</div>
        <div className="text-[12px] capitalize text-muted">{role}</div>
        <button onClick={logout} className="mt-2 text-[13px] text-muted hover:text-ink">Log out</button>
      </div>
    </nav>
  );

  return (
    <SessionContext.Provider value={{ ...me.data, reload: me.reload }}>
      <div className="flex min-h-screen">
        <aside className="sticky top-0 hidden h-screen w-60 shrink-0 border-r border-line bg-surface lg:block">{sidebar}</aside>
        {menu && (
          <div className="fixed inset-0 z-40 bg-black/30 lg:hidden" onClick={() => setMenu(false)}>
            <aside className="h-full w-64 bg-surface" onClick={(e) => e.stopPropagation()}>{sidebar}</aside>
          </div>
        )}
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-3 border-b border-line bg-surface px-4 py-3 lg:hidden">
            <button onClick={() => setMenu(true)} className="rounded border border-line px-2 py-1" aria-label="Menu">☰</button>
            <span className="font-semibold">{me.data.organization.name}</span>
          </div>
          <main className="mx-auto max-w-6xl px-4 py-8 sm:px-8">
            {allowed ? children : (
              <div className="py-20 text-center">
                <div className="text-lg font-semibold">You don&apos;t have access to this page</div>
                <p className="mt-1 text-muted">Your role ({role}) can&apos;t open {owner?.label}. Ask an admin if you need it.</p>
                <Link href="/" className="mt-4 inline-block font-medium text-brand-600">Back to Home</Link>
              </div>
            )}
          </main>
        </div>
      </div>
    </SessionContext.Provider>
  );
}
