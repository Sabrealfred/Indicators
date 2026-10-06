'use client';

import { useState } from 'react';
import { del, post, useApi } from '@/lib/api';
import type { GlAccount } from '@/lib/types';
import { Badge, Button, Card, Empty, ErrorNote, Field, Input, Loading, Modal, PageHeader, Select, Table, Td, Th, useAction } from '@/components/ui';
import { GlSelect, type Rule } from '../_shared';

const FIELDS: Record<string, string> = { counterparty: 'Counterparty', description: 'Description', merchant_category: 'Merchant category' };

function AddRuleModal({ open, onClose, onDone, accounts, categories }: { open: boolean; onClose: () => void; onDone: (applied: number | null) => void; accounts: GlAccount[]; categories: string[] }) {
  const [field, setField] = useState('counterparty');
  const [value, setValue] = useState('');
  const [gl, setGl] = useState('');
  const [priority, setPriority] = useState('100');
  const [apply, setApply] = useState(true);
  const a = useAction();
  const prio = Number(priority);
  const ok = value.trim() && gl && Number.isInteger(prio);

  const submit = async () => {
    const r = await a.run(() => post<{ id: string; applied: number }>('/books/rules', {
      match_field: field, match_value: value.trim(), gl_account_id: gl, priority: prio, apply_to_existing: apply,
    }));
    if (r) {
      setValue(''); setGl(''); setPriority('100');
      onDone(apply ? r.applied : null);
    }
  };

  return (
    <Modal open={open} onClose={onClose} title="New categorization rule" footer={
      <>
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        <Button variant="primary" disabled={!ok || a.busy} onClick={submit}>{a.busy ? 'Saving…' : 'Create rule'}</Button>
      </>
    }>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="When">
          <Select value={field} onChange={(e) => { setField(e.target.value); setValue(''); }}>
            {Object.entries(FIELDS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </Select>
        </Field>
        <Field label="Contains" hint="Case-insensitive substring match">
          {field === 'merchant_category' ? (
            <Select value={value} onChange={(e) => setValue(e.target.value)}>
              <option value="">Select category…</option>
              {categories.map((c) => <option key={c} value={c}>{c.replace(/_/g, ' ')}</option>)}
            </Select>
          ) : (
            <Input value={value} onChange={(e) => setValue(e.target.value)} placeholder={field === 'counterparty' ? 'e.g. AWS' : 'e.g. payroll'} autoFocus />
          )}
        </Field>
      </div>
      <Field label="Categorize as">
        <GlSelect accounts={accounts} value={gl} onChange={setGl} types={['revenue', 'expense']} />
      </Field>
      <Field label="Priority" hint="Lower numbers run first; the first matching rule wins.">
        <Input type="number" value={priority} onChange={(e) => setPriority(e.target.value)} className="w-32" />
      </Field>
      <label className="flex items-start gap-2 text-[13px]">
        <input type="checkbox" className="mt-0.5" checked={apply} onChange={(e) => setApply(e.target.checked)} />
        <span>Apply to existing uncategorized transactions<span className="block text-muted">Re-books posted transactions currently in Uncategorized Income/Expense (open periods only).</span></span>
      </label>
      <ErrorNote error={a.error} />
    </Modal>
  );
}

export default function RulesPage() {
  const rules = useApi<Rule[]>('/books/rules');
  const coa = useApi<GlAccount[]>('/books/accounts');
  const cats = useApi<string[]>('/merchant-categories');
  const [adding, setAdding] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const rm = useAction();

  const remove = async (r: Rule) => {
    if (!confirm(`Delete the rule "${FIELDS[r.match_field]} contains ${r.match_value}"? Already-categorized transactions are not changed.`)) return;
    const ok = await rm.run(() => del(`/books/rules/${r.id}`));
    if (ok) rules.reload();
  };

  return (
    <>
      <PageHeader
        title="Categorization rules"
        subtitle="Automatically assign GL accounts to new bank and card transactions."
        actions={<Button variant="primary" onClick={() => setAdding(true)} disabled={!coa.data}>Add rule</Button>}
      />
      <div className="mb-4 space-y-2">
        {msg && <div className="rounded-lg border border-emerald-500/30 bg-emerald-500/5 px-3 py-2 text-[13px] text-pos">{msg}</div>}
        <ErrorNote error={rules.error || coa.error || rm.error} />
      </div>
      <Card padded={false}>
        {!rules.data ? (!rules.error && <Loading />) : !rules.data.length ? (
          <Empty title="No rules yet" hint="Rules run before counterparty defaults and merchant categories." action={<Button onClick={() => setAdding(true)}>Add your first rule</Button>} />
        ) : (
          <Table>
            <thead>
              <tr><Th className="w-20">Priority</Th><Th>When</Th><Th>Contains</Th><Th>Categorize as</Th><Th className="w-20" /></tr>
            </thead>
            <tbody>
              {rules.data.map((r) => (
                <tr key={r.id} className="hover:bg-surface-2">
                  <Td className="tabular text-muted">{r.priority}</Td>
                  <Td><Badge>{FIELDS[r.match_field] ?? r.match_field}</Badge></Td>
                  <Td className="font-medium">“{r.match_value}”</Td>
                  <Td>→ <span className="text-muted tabular">{r.code}</span> {r.gl_name}</Td>
                  <Td right><Button size="sm" variant="danger" onClick={() => remove(r)} disabled={rm.busy}>Delete</Button></Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
        <p className="border-t border-line px-5 py-3 text-[12px] text-muted">
          Order of precedence: explicit category → rules (by priority) → recipient default GL account → merchant category → uncategorized.
        </p>
      </Card>
      {coa.data && (
        <AddRuleModal
          open={adding}
          onClose={() => setAdding(false)}
          accounts={coa.data}
          categories={cats.data ?? []}
          onDone={(applied) => {
            setAdding(false);
            setMsg(applied === null ? 'Rule created. It will apply to new transactions.' : `Rule created — ${applied} existing transaction${applied === 1 ? '' : 's'} re-categorized.`);
            rules.reload();
          }}
        />
      )}
    </>
  );
}
