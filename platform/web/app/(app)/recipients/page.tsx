'use client';

import { useMemo, useState, type ReactNode } from 'react';
import { del, patch, post, useApi } from '@/lib/api';
import { can, useSession } from '@/lib/session';
import type { GlAccount, Recipient } from '@/lib/types';
import { Badge, Button, Card, Empty, ErrorNote, Field, Input, Loading, Modal, PageHeader, Select, Table, Td, Th, Tr, useAction } from '@/components/ui';
import { GlOptions } from '../transactions/_tx';

function recipientMethods(r: Recipient) {
  return {
    ach: !!r.ach_account,
    wire: !!r.wire_account,
    intl: !!r.iban,
    check: !!r.address,
  };
}

export default function RecipientsPage() {
  const { user } = useSession();
  const allowed = can.moveMoney(user.role);
  const list = useApi<Recipient[]>(allowed ? '/recipients' : null);
  const gl = useApi<GlAccount[]>(allowed ? '/books/accounts' : null);
  const [q, setQ] = useState('');
  const [kind, setKind] = useState<'' | 'vendor' | 'customer'>('');
  const [editing, setEditing] = useState<Recipient | 'new' | null>(null);

  const rows = useMemo(() => {
    const s = q.trim().toLowerCase();
    return (list.data ?? []).filter(
      (r) =>
        (!s || r.name.toLowerCase().includes(s) || (r.email ?? '').toLowerCase().includes(s)) &&
        (!kind || (kind === 'vendor' ? r.is_vendor : r.is_customer)),
    );
  }, [list.data, q, kind]);

  if (!allowed) {
    return (
      <>
        <PageHeader title="Recipients" />
        <Card><Empty title="Recipients are managed by admins and bookkeepers" hint="Ask an admin if you need to pay someone. You can request reimbursements from the Reimbursements page." /></Card>
      </>
    );
  }

  const glName = (id: string | null) => {
    const g = id ? gl.data?.find((x) => x.id === id) : undefined;
    return g ? g.name : null;
  };

  return (
    <>
      <PageHeader
        title="Recipients"
        subtitle="Vendors, customers and people you pay."
        actions={<Button variant="primary" onClick={() => setEditing('new')}>Add recipient</Button>}
      />
      <div className="mb-4 flex flex-wrap gap-3">
        <Input type="search" placeholder="Search by name or email" value={q} onChange={(e) => setQ(e.target.value)} className="max-w-xs" />
        <Select value={kind} onChange={(e) => setKind(e.target.value as typeof kind)} className="w-auto" aria-label="Filter">
          <option value="">All recipients</option>
          <option value="vendor">Vendors</option>
          <option value="customer">Customers</option>
        </Select>
      </div>
      <ErrorNote error={list.error} />
      <Card padded={false}>
        {!list.data ? (list.error ? null : <Loading />) : !rows.length ? (
          <Empty
            title={list.data.length ? 'No matching recipients' : 'No recipients yet'}
            hint={list.data.length ? 'Try a different search.' : 'Add a vendor or customer to send payments.'}
            action={!list.data.length && <Button variant="primary" onClick={() => setEditing('new')}>Add recipient</Button>}
          />
        ) : (
          <Table>
            <thead>
              <tr>
                <Th>Name</Th>
                <Th>Payment methods</Th>
                <Th>Tags</Th>
                <Th>Default category</Th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => {
                const m = recipientMethods(r);
                return (
                  <Tr key={r.id} onClick={() => setEditing(r)}>
                    <Td className="min-w-48">
                      <div className="font-medium">{r.name}</div>
                      <div className="text-[12px] text-muted">
                        {r.type === 'individual' ? 'Individual' : 'Business'}
                        {r.email ? ` · ${r.email}` : ''}
                        {r.country !== 'US' ? ` · ${r.country}` : ''}
                      </div>
                    </Td>
                    <Td className="min-w-40">
                      <div className="flex flex-wrap gap-1">
                        {m.ach && <Badge tone="blue">ACH</Badge>}
                        {m.wire && <Badge tone="blue">Wire</Badge>}
                        {m.intl && <Badge tone="blue">Intl</Badge>}
                        {m.check && <Badge>Check</Badge>}
                        {!m.ach && !m.wire && !m.intl && !m.check && <span className="text-[13px] text-muted">None</span>}
                      </div>
                    </Td>
                    <Td>
                      <div className="flex flex-wrap gap-1">
                        {r.is_vendor ? <Badge tone="yellow">Vendor</Badge> : null}
                        {r.is_customer ? <Badge tone="green">Customer</Badge> : null}
                      </div>
                    </Td>
                    <Td className="min-w-32 text-[13px] text-muted">{glName(r.default_gl_account_id) ?? '—'}</Td>
                  </Tr>
                );
              })}
            </tbody>
          </Table>
        )}
      </Card>
      {editing && (
        <RecipientModal
          recipient={editing === 'new' ? null : editing}
          gl={gl.data ?? []}
          canDelete={can.admin(user.role)}
          onClose={() => setEditing(null)}
          onDone={() => { setEditing(null); list.reload(); }}
        />
      )}
    </>
  );
}

type Form = {
  name: string; email: string; type: 'business' | 'individual'; ach_routing: string; ach_account: string; wire_routing: string; wire_account: string;
  swift: string; iban: string; country: string; address: string; default_gl_account_id: string; is_customer: boolean; is_vendor: boolean;
};

const fromRecipient = (r: Recipient | null): Form => ({
  name: r?.name ?? '', email: r?.email ?? '', type: r?.type ?? 'business', ach_routing: r?.ach_routing ?? '', ach_account: r?.ach_account ?? '',
  wire_routing: r?.wire_routing ?? '', wire_account: r?.wire_account ?? '', swift: r?.swift ?? '', iban: r?.iban ?? '', country: r?.country ?? 'US',
  address: r?.address ?? '', default_gl_account_id: r?.default_gl_account_id ?? '', is_customer: !!r?.is_customer, is_vendor: r ? !!r.is_vendor : true,
});

function Section({ title, hint, children }: { title: string; hint?: string; children: ReactNode }) {
  return (
    <fieldset className="space-y-3 border-t border-line pt-4">
      <legend className="sr-only">{title}</legend>
      <div>
        <div className="text-[13px] font-semibold">{title}</div>
        {hint && <div className="text-[12px] text-muted">{hint}</div>}
      </div>
      {children}
    </fieldset>
  );
}

function RecipientModal({ recipient, gl, canDelete, onClose, onDone }: { recipient: Recipient | null; gl: GlAccount[]; canDelete: boolean; onClose: () => void; onDone: () => void }) {
  const [f, setF] = useState<Form>(() => fromRecipient(recipient));
  const [touched, setTouched] = useState(false);
  const act = useAction();
  const set = <K extends keyof Form>(k: K, v: Form[K]) => setF((p) => ({ ...p, [k]: v }));
  const digits = (s: string) => s.replace(/\D/g, '');

  const errors: Partial<Record<keyof Form, string>> = {};
  if (!f.name.trim()) errors.name = 'Name is required';
  if (f.email && !/^\S+@\S+\.\S+$/.test(f.email)) errors.email = 'Enter a valid email';
  if (f.ach_routing && !/^\d{9}$/.test(f.ach_routing)) errors.ach_routing = 'Routing numbers are 9 digits';
  if (f.wire_routing && !/^\d{9}$/.test(f.wire_routing)) errors.wire_routing = 'Routing numbers are 9 digits';
  if (f.ach_account && !/^\d{4,17}$/.test(f.ach_account)) errors.ach_account = '4–17 digits';
  if (f.wire_account && !/^\d{4,17}$/.test(f.wire_account)) errors.wire_account = '4–17 digits';
  if (!!f.ach_routing !== !!f.ach_account) errors[f.ach_routing ? 'ach_account' : 'ach_routing'] = 'Both routing and account number are needed';
  if (!!f.wire_routing !== !!f.wire_account) errors[f.wire_routing ? 'wire_account' : 'wire_routing'] = 'Both routing and account number are needed';
  if (f.swift && !/^[A-Z0-9]{8,11}$/.test(f.swift)) errors.swift = 'SWIFT/BIC is 8 or 11 characters';
  if (f.iban && (f.iban.length < 10 || f.iban.length > 34)) errors.iban = 'IBAN is 10–34 characters';
  if (!/^[A-Z]{2}$/.test(f.country)) errors.country = 'Two-letter country code';
  const err = (k: keyof Form) => (touched || (f[k] as string)?.length ? errors[k] ?? null : null);
  const valid = Object.keys(errors).length === 0;

  const submit = async () => {
    setTouched(true);
    if (!valid) return;
    const n = (s: string) => (s.trim() ? s.trim() : null);
    const body = {
      name: f.name.trim(), email: n(f.email), type: f.type, ach_routing: n(f.ach_routing), ach_account: n(f.ach_account),
      wire_routing: n(f.wire_routing), wire_account: n(f.wire_account), swift: n(f.swift), iban: n(f.iban), country: f.country,
      address: n(f.address), default_gl_account_id: n(f.default_gl_account_id), is_customer: f.is_customer, is_vendor: f.is_vendor,
    };
    const r = await act.run(() => (recipient ? patch(`/recipients/${recipient.id}`, body) : post('/recipients', body)));
    if (r) onDone();
  };

  const remove = async () => {
    if (!recipient || !confirm(`Delete ${recipient.name}?`)) return;
    const r = await act.run(() => del(`/recipients/${recipient.id}`));
    if (r) onDone();
  };

  return (
    <Modal
      open
      wide
      onClose={onClose}
      title={recipient ? `Edit ${recipient.name}` : 'Add recipient'}
      footer={
        <div className="flex w-full items-center justify-between gap-2">
          <div>{recipient && canDelete && <Button variant="danger" disabled={act.busy} onClick={remove}>Delete</Button>}</div>
          <div className="flex gap-2">
            <Button onClick={onClose}>Cancel</Button>
            <Button variant="primary" disabled={act.busy || (touched && !valid)} onClick={submit}>{act.busy ? 'Saving…' : recipient ? 'Save changes' : 'Add recipient'}</Button>
          </div>
        </div>
      }
    >
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Name" error={touched ? errors.name : null}>
          <Input autoFocus value={f.name} onChange={(e) => set('name', e.target.value)} placeholder="Acme Supplies Inc." />
        </Field>
        <Field label="Email (optional)" error={err('email')}>
          <Input type="email" value={f.email} onChange={(e) => set('email', e.target.value)} placeholder="ap@acme.com" />
        </Field>
        <Field label="Type">
          <Select value={f.type} onChange={(e) => set('type', e.target.value as Form['type'])}>
            <option value="business">Business</option>
            <option value="individual">Individual</option>
          </Select>
        </Field>
        <div className="flex items-end gap-5 pb-2">
          <label className="flex items-center gap-2">
            <input type="checkbox" className="h-4 w-4 accent-brand-600" checked={f.is_vendor} onChange={(e) => set('is_vendor', e.target.checked)} /> Vendor
          </label>
          <label className="flex items-center gap-2">
            <input type="checkbox" className="h-4 w-4 accent-brand-600" checked={f.is_customer} onChange={(e) => set('is_customer', e.target.checked)} /> Customer
          </label>
        </div>
      </div>

      <Section title="ACH" hint="For standard and same-day ACH payments.">
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Routing number" error={err('ach_routing')}>
            <Input inputMode="numeric" maxLength={9} value={f.ach_routing} onChange={(e) => set('ach_routing', digits(e.target.value))} placeholder="9 digits" className="tabular" />
          </Field>
          <Field label="Account number" error={err('ach_account')}>
            <Input inputMode="numeric" maxLength={17} value={f.ach_account} onChange={(e) => set('ach_account', digits(e.target.value))} className="tabular" />
          </Field>
        </div>
      </Section>

      <Section title="Domestic wire">
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Routing number" error={err('wire_routing')}>
            <Input inputMode="numeric" maxLength={9} value={f.wire_routing} onChange={(e) => set('wire_routing', digits(e.target.value))} placeholder="9 digits" className="tabular" />
          </Field>
          <Field label="Account number" error={err('wire_account')}>
            <Input inputMode="numeric" maxLength={17} value={f.wire_account} onChange={(e) => set('wire_account', digits(e.target.value))} className="tabular" />
          </Field>
        </div>
        {!f.wire_routing && !f.wire_account && f.ach_routing && f.ach_account && (
          <Button size="sm" variant="ghost" onClick={() => setF((p) => ({ ...p, wire_routing: p.ach_routing, wire_account: p.ach_account }))}>Same as ACH</Button>
        )}
      </Section>

      <Section title="International wire" hint="IBAN is required to send international wires.">
        <div className="grid gap-3 sm:grid-cols-[1fr_1fr_100px]">
          <Field label="SWIFT / BIC" error={err('swift')}>
            <Input value={f.swift} maxLength={11} onChange={(e) => set('swift', e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, ''))} placeholder="DEUTDEFF" />
          </Field>
          <Field label="IBAN" error={err('iban')}>
            <Input value={f.iban} maxLength={34} onChange={(e) => set('iban', e.target.value.toUpperCase().replace(/\s/g, ''))} placeholder="DE89370400440532013000" />
          </Field>
          <Field label="Country" error={err('country')}>
            <Input value={f.country} maxLength={2} onChange={(e) => set('country', e.target.value.toUpperCase().replace(/[^A-Z]/g, ''))} placeholder="US" />
          </Field>
        </div>
      </Section>

      <Section title="Mailing address" hint="Required for mailed checks.">
        <Input value={f.address} onChange={(e) => set('address', e.target.value)} placeholder="100 Main St, New York, NY 10001" />
      </Section>

      <Section title="Bookkeeping">
        <Field label="Default category" hint="Payments to this recipient are categorized here automatically.">
          <Select value={f.default_gl_account_id} onChange={(e) => set('default_gl_account_id', e.target.value)}>
            <option value="">None</option>
            <GlOptions accounts={gl} types={['expense', 'revenue']} />
          </Select>
        </Field>
      </Section>

      <ErrorNote error={act.error} />
    </Modal>
  );
}
