'use client';

import { useState } from 'react';
import { patch, post, useApi } from '@/lib/api';
import { can, useSession } from '@/lib/session';
import { date } from '@/lib/format';
import type { Role, TeamMember } from '@/lib/types';
import { Badge, Button, Card, Empty, ErrorNote, Field, Input, Loading, Modal, PageHeader, Select, StatusBadge, Table, Td, Th, useAction } from '@/components/ui';

const ROLES: { value: Role; label: string; hint: string }[] = [
  { value: 'admin', label: 'Admin', hint: 'Full access, approves payments, manages team and settings' },
  { value: 'bookkeeper', label: 'Bookkeeper', hint: 'Books, bills and invoices; money movement needs admin approval' },
  { value: 'employee', label: 'Employee', hint: 'Own cards, transactions and reimbursements' },
];
const ROLE_TONE: Record<Role, 'blue' | 'yellow' | 'gray'> = { admin: 'blue', bookkeeper: 'yellow', employee: 'gray' };

function InviteModal({ open, onClose, onDone }: { open: boolean; onClose: () => void; onDone: (m: TeamMember, pw: string) => void }) {
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [role, setRole] = useState<Role>('employee');
  const [password, setPassword] = useState('');
  const a = useAction();
  const pwOk = !password || password.length >= 8;
  const ok = name.trim() && /^\S+@\S+\.\S+$/.test(email) && pwOk;

  const submit = async () => {
    const r = await a.run(() => post<TeamMember>('/team', { name: name.trim(), email: email.trim(), role, ...(password ? { password } : {}) }));
    if (r) {
      onDone(r, password || 'password123');
      setName(''); setEmail(''); setPassword(''); setRole('employee');
    }
  };

  return (
    <Modal open={open} onClose={onClose} title="Invite team member" footer={
      <>
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        <Button variant="primary" disabled={!ok || a.busy} onClick={submit}>{a.busy ? 'Inviting…' : 'Send invite'}</Button>
      </>
    }>
      <Field label="Full name"><Input value={name} onChange={(e) => setName(e.target.value)} autoFocus /></Field>
      <Field label="Work email"><Input type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="name@company.com" /></Field>
      <Field label="Role" hint={ROLES.find((r) => r.value === role)?.hint}>
        <Select value={role} onChange={(e) => setRole(e.target.value as Role)}>
          {ROLES.map((r) => <option key={r.value} value={r.value}>{r.label}</option>)}
        </Select>
      </Field>
      <Field label="Temporary password (optional)" hint="Leave blank to use the default password: password123" error={!pwOk ? 'At least 8 characters' : null}>
        <Input type="text" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="password123" autoComplete="new-password" />
      </Field>
      <ErrorNote error={a.error} />
    </Modal>
  );
}

function MemberRow({ m, isAdmin, isSelf, onChanged }: { m: TeamMember; isAdmin: boolean; isSelf: boolean; onChanged: () => void }) {
  const a = useAction();
  const update = async (body: { role?: Role; status?: 'active' | 'disabled' }) => {
    const r = await a.run(() => patch(`/team/${m.id}`, body));
    if (r) onChanged();
  };
  const disabled = m.status === 'disabled';
  return (
    <tr className="hover:bg-surface-2">
      <Td>
        <div className="font-medium">{m.name}{isSelf && <span className="ml-1.5 text-[12px] font-normal text-muted">(you)</span>}</div>
        <div className="text-[12px] text-muted">{m.email}</div>
      </Td>
      <Td>
        {isAdmin && !isSelf ? (
          <Select
            value={m.role}
            disabled={a.busy}
            onChange={(e) => {
              const role = e.target.value as Role;
              if (confirm(`Change ${m.name}'s role to ${role}?`)) update({ role });
            }}
            className="h-8 w-36 text-[13px]"
          >
            {ROLES.map((r) => <option key={r.value} value={r.value}>{r.label}</option>)}
          </Select>
        ) : (
          <Badge tone={ROLE_TONE[m.role]}>{m.role.charAt(0).toUpperCase() + m.role.slice(1)}</Badge>
        )}
      </Td>
      <Td><StatusBadge status={m.status} /></Td>
      <Td right>{m.active_cards}</Td>
      <Td className="whitespace-nowrap text-muted">{date(m.created_at)}</Td>
      {isAdmin && (
        <Td right>
          {!isSelf && (disabled ? (
            <Button size="sm" onClick={() => update({ status: 'active' })} disabled={a.busy}>Reactivate</Button>
          ) : (
            <Button size="sm" variant="danger" disabled={a.busy} onClick={() => confirm(`Disable ${m.name}? They will be signed out and their cards frozen.`) && update({ status: 'disabled' })}>Disable</Button>
          ))}
          {a.error && <div className="mt-1 text-[12px] text-neg">{a.error}</div>}
        </Td>
      )}
    </tr>
  );
}

export default function TeamPage() {
  const { user } = useSession();
  const isAdmin = can.admin(user.role);
  const team = useApi<TeamMember[]>('/team');
  const [inviting, setInviting] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const counts = (team.data ?? []).reduce<Record<string, number>>((acc, m) => ({ ...acc, [m.role]: (acc[m.role] ?? 0) + 1 }), {});

  return (
    <>
      <PageHeader
        title="Team"
        subtitle={team.data ? `${team.data.length} members · ${counts.admin ?? 0} admin · ${counts.bookkeeper ?? 0} bookkeeper · ${counts.employee ?? 0} employee` : 'People with access to this LedgerBank workspace.'}
        actions={isAdmin && <Button variant="primary" onClick={() => setInviting(true)}>Invite member</Button>}
      />
      <div className="mb-4 space-y-2">
        {notice && <div className="rounded-lg border border-emerald-500/30 bg-emerald-500/5 px-3 py-2 text-[13px] text-pos">{notice}</div>}
        {!isAdmin && <div className="rounded-lg border border-line bg-surface-2 px-3 py-2 text-[13px] text-muted">Only admins can invite people or change roles.</div>}
        <ErrorNote error={team.error} />
      </div>
      <Card padded={false}>
        {!team.data ? (!team.error && <Loading />) : !team.data.length ? <Empty title="No team members" /> : (
          <Table>
            <thead>
              <tr><Th>Member</Th><Th>Role</Th><Th>Status</Th><Th right>Active cards</Th><Th>Added</Th>{isAdmin && <Th right />}</tr>
            </thead>
            <tbody>
              {team.data.map((m) => <MemberRow key={m.id} m={m} isAdmin={isAdmin} isSelf={m.id === user.id} onChanged={team.reload} />)}
            </tbody>
          </Table>
        )}
      </Card>
      {isAdmin && (
        <InviteModal
          open={inviting}
          onClose={() => setInviting(false)}
          onDone={(m, pw) => {
            setInviting(false);
            setNotice(`${m.name} was added as ${m.role}. They can sign in at /login with ${m.email} and the temporary password "${pw}".`);
            team.reload();
          }}
        />
      )}
    </>
  );
}
