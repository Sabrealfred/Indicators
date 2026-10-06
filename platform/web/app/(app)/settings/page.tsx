'use client';

import { Suspense, useEffect, useState } from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { del, patch, post, useApi } from '@/lib/api';
import { can, useSession } from '@/lib/session';
import { date, dateTime, money } from '@/lib/format';
import { Badge, Button, Card, Empty, ErrorNote, Field, Input, KeyValue, Loading, Modal, MoneyInput, PageHeader, Table, Tabs, Td, Textarea, Th, useAction } from '@/components/ui';

type Tab = 'company' | 'keys' | 'audit';
interface ApiKey { id: string; name: string; prefix: string; created_at: string; revoked_at: string | null }
interface AuditEvent { id: number; user_id: string | null; user_name: string | null; action: string; entity_type: string; entity_id: string | null; details: unknown; created_at: string }

// ---------- Company ----------

function Company() {
  const { organization, reload } = useSession();
  const [name, setName] = useState(organization.name);
  const [address, setAddress] = useState(organization.address);
  const [threshold, setThreshold] = useState<number | null>(organization.approval_threshold);
  const [saved, setSaved] = useState(false);
  const a = useAction();

  const dirty = name !== organization.name || address !== organization.address || threshold !== organization.approval_threshold;
  const ok = name.trim() && address.trim() && threshold != null && threshold > 0;

  const save = async () => {
    setSaved(false);
    const body: Record<string, unknown> = {};
    if (name !== organization.name) body.name = name.trim();
    if (address !== organization.address) body.address = address.trim();
    if (threshold !== organization.approval_threshold) body.approval_threshold = threshold;
    const r = await a.run(() => patch('/organization', body));
    if (r) { setSaved(true); reload(); }
  };

  return (
    <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
      <Card title="Company profile" className="lg:col-span-2">
        <div className="space-y-4">
          <Field label="Display name" hint="Shown in the sidebar, on invoices and to your team.">
            <Input value={name} onChange={(e) => setName(e.target.value)} />
          </Field>
          <Field label="Business address">
            <Textarea value={address} onChange={(e) => setAddress(e.target.value)} rows={2} />
          </Field>
          <Field
            label="Payment approval threshold"
            hint={<>Outgoing payments of this amount or more need an admin&apos;s approval — and a <em>different</em> admin than the one who created them. Payments created by non-admins always need approval.</>}
          >
            <div className="max-w-56"><MoneyInput value={threshold} onChange={setThreshold} /></div>
          </Field>
          <ErrorNote error={a.error} />
          <div className="flex items-center gap-3">
            <Button variant="primary" disabled={!dirty || !ok || a.busy} onClick={save}>{a.busy ? 'Saving…' : 'Save changes'}</Button>
            {saved && !dirty && <span className="text-[13px] text-pos">✓ Saved</span>}
          </div>
        </div>
      </Card>
      <Card title="Legal entity">
        <KeyValue rows={[
          ['Legal name', organization.legal_name],
          ['EIN', <span key="ein" className="tabular">{organization.ein}</span>],
          ['Approval threshold', money(organization.approval_threshold)],
          ['Books locked through', organization.books_locked_through ? date(organization.books_locked_through) : 'Open'],
        ]} />
        <p className="mt-3 text-[12px] text-muted">Legal name and EIN are verified at onboarding; contact support to change them.</p>
      </Card>
    </div>
  );
}

// ---------- API keys ----------

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <Button size="sm" onClick={async () => {
      try { await navigator.clipboard.writeText(text); setCopied(true); setTimeout(() => setCopied(false), 1500); } catch { /* clipboard blocked */ }
    }}>{copied ? 'Copied ✓' : 'Copy'}</Button>
  );
}

function ApiKeys() {
  const keys = useApi<ApiKey[]>('/api-keys');
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [secret, setSecret] = useState<{ name: string; secret: string } | null>(null);
  const [origin, setOrigin] = useState('http://localhost:3000');
  useEffect(() => setOrigin(window.location.origin), []);
  const a = useAction();
  const rv = useAction();

  const create = async () => {
    const r = await a.run(() => post<{ id: string; name: string; secret: string }>('/api-keys', { name: name.trim() }));
    if (r) { setCreating(false); setName(''); setSecret(r); keys.reload(); }
  };
  const revoke = async (k: ApiKey) => {
    if (!confirm(`Revoke "${k.name}"? Any integration using it will stop working immediately.`)) return;
    const r = await rv.run(() => del(`/api-keys/${k.id}`));
    if (r) keys.reload();
  };

  const curl = (key: string) => `curl ${origin}/api/accounts \\\n  -H "Authorization: Bearer ${key}"`;

  return (
    <>
      <Card
        title="API keys"
        padded={false}
        actions={<Button size="sm" variant="primary" onClick={() => setCreating(true)}>Create key</Button>}
      >
        <div className="px-5 pt-3"><ErrorNote error={keys.error || rv.error} /></div>
        {!keys.data ? (!keys.error && <Loading />) : !keys.data.length ? (
          <Empty title="No API keys" hint="Create a key to access your accounts, transactions and payments programmatically." />
        ) : (
          <Table>
            <thead><tr><Th>Name</Th><Th>Key</Th><Th>Created</Th><Th>Status</Th><Th right /></tr></thead>
            <tbody>
              {keys.data.map((k) => (
                <tr key={k.id} className="hover:bg-surface-2">
                  <Td className="font-medium">{k.name}</Td>
                  <Td><code className="rounded bg-surface-2 px-1.5 py-0.5 text-[12px]">{k.prefix}…</code></Td>
                  <Td className="whitespace-nowrap text-muted">{dateTime(k.created_at)}</Td>
                  <Td>{k.revoked_at ? <Badge>Revoked {date(k.revoked_at)}</Badge> : <Badge tone="green">Active</Badge>}</Td>
                  <Td right>{!k.revoked_at && <Button size="sm" variant="danger" onClick={() => revoke(k)} disabled={rv.busy}>Revoke</Button>}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>

      <Card title="Using the API" className="mt-6">
        <p className="mb-3 text-[13px] text-muted">Send the key as a bearer token. Keys act with the permissions of the admin who created them; amounts are integer cents.</p>
        <pre className="overflow-x-auto rounded-lg bg-surface-2 p-3 text-[12px] leading-relaxed">{curl('lb_live_…')}</pre>
      </Card>

      <Modal open={creating} onClose={() => setCreating(false)} title="Create API key" footer={
        <>
          <Button variant="ghost" onClick={() => setCreating(false)}>Cancel</Button>
          <Button variant="primary" disabled={!name.trim() || a.busy} onClick={create}>{a.busy ? 'Creating…' : 'Create key'}</Button>
        </>
      }>
        <Field label="Name" hint="What will use this key, e.g. “Accounting sync”."><Input value={name} onChange={(e) => setName(e.target.value)} autoFocus /></Field>
        <ErrorNote error={a.error} />
      </Modal>

      <Modal open={!!secret} onClose={() => setSecret(null)} title="Your new API key" wide footer={<Button variant="primary" onClick={() => setSecret(null)}>I&apos;ve saved it</Button>}>
        {secret && (
          <>
            <div className="rounded-lg border border-amber-500/30 bg-amber-500/5 px-3 py-2 text-[13px] text-amber-800 dark:text-amber-300">
              Copy this secret now — it will not be shown again. Store it like a password; anyone with it can act as you.
            </div>
            <Field label={secret.name}>
              <div className="flex gap-2">
                <Input readOnly value={secret.secret} onFocus={(e) => e.currentTarget.select()} className="font-mono text-[13px]" />
                <CopyButton text={secret.secret} />
              </div>
            </Field>
            <div>
              <div className="mb-1 flex items-center justify-between">
                <span className="text-[13px] font-medium">Try it</span>
                <CopyButton text={curl(secret.secret)} />
              </div>
              <pre className="overflow-x-auto rounded-lg bg-surface-2 p-3 text-[12px] leading-relaxed">{curl(secret.secret)}</pre>
            </div>
          </>
        )}
      </Modal>
    </>
  );
}

// ---------- Audit log ----------

function compact(details: unknown) {
  if (details == null) return '';
  const s = JSON.stringify(details);
  return s === '{}' ? '' : s;
}

function AuditLog() {
  const log = useApi<AuditEvent[]>('/audit');
  const [q, setQ] = useState('');
  const rows = (log.data ?? []).filter((e) => !q || `${e.action} ${e.entity_type} ${e.user_name ?? ''} ${compact(e.details)}`.toLowerCase().includes(q.toLowerCase()));
  return (
    <Card title="Audit log" padded={false} actions={<Input placeholder="Filter…" value={q} onChange={(e) => setQ(e.target.value)} className="h-8 w-44 text-[13px]" />}>
      <div className="px-5 pt-3"><ErrorNote error={log.error} /></div>
      {!log.data ? (!log.error && <Loading />) : !rows.length ? <Empty title="No events" /> : (
        <Table>
          <thead><tr><Th>Time</Th><Th>User</Th><Th>Action</Th><Th>Entity</Th><Th>Details</Th></tr></thead>
          <tbody>
            {rows.map((e) => (
              <tr key={e.id} className="text-[13px] hover:bg-surface-2">
                <Td className="whitespace-nowrap py-2 text-muted">{dateTime(e.created_at)}</Td>
                <Td className="whitespace-nowrap py-2">{e.user_name ?? <span className="text-muted">System</span>}</Td>
                <Td className="py-2"><code className="rounded bg-surface-2 px-1.5 py-0.5 text-[12px]">{e.action}</code></Td>
                <Td className="whitespace-nowrap py-2">
                  <span className="capitalize">{e.entity_type.replace(/_/g, ' ')}</span>
                  {e.entity_id && <div className="font-mono text-[11px] text-muted">{e.entity_id}</div>}
                </Td>
                <Td className="py-2"><div className="max-w-[360px] truncate font-mono text-[12px] text-muted" title={compact(e.details)}>{compact(e.details)}</div></Td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}
      <p className="border-t border-line px-5 py-3 text-[12px] text-muted">Showing the latest 200 events.</p>
    </Card>
  );
}

// ---------- Page ----------

function SettingsInner() {
  const { user } = useSession();
  const params = useSearchParams();
  const router = useRouter();
  const path = usePathname();
  const initial = params.get('tab') as Tab | null;
  const [tab, setTab] = useState<Tab>(initial && ['company', 'keys', 'audit'].includes(initial) ? initial : 'company');

  if (!can.admin(user.role)) {
    return (
      <>
        <PageHeader title="Settings" />
        <Card><Empty title="Admins only" hint="Ask an admin on your team to change company settings." /></Card>
      </>
    );
  }

  return (
    <>
      <PageHeader title="Settings" subtitle="Company profile, API access and audit trail." />
      <Tabs
        value={tab}
        onChange={(t) => { setTab(t); router.replace(`${path}?tab=${t}`, { scroll: false }); }}
        tabs={[{ value: 'company', label: 'Company' }, { value: 'keys', label: 'API keys' }, { value: 'audit', label: 'Audit log' }]}
      />
      {tab === 'company' && <Company />}
      {tab === 'keys' && <ApiKeys />}
      {tab === 'audit' && <AuditLog />}
    </>
  );
}

export default function SettingsPage() {
  return (
    <Suspense fallback={<Loading />}>
      <SettingsInner />
    </Suspense>
  );
}
