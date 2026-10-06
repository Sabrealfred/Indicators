import { all, run } from '../db.js';
import { nowIso } from '../clock.js';

export function audit(actor: { id: string; org_id: string } | null, action: string, entityType: string, entityId?: string | null, details?: unknown, orgId?: string) {
  run(
    'INSERT INTO audit_log(org_id, user_id, action, entity_type, entity_id, details, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
    actor?.org_id ?? orgId ?? 'system', actor?.id ?? null, action, entityType, entityId ?? null, details === undefined ? null : JSON.stringify(details), nowIso(),
  );
}

export function listAudit(orgId: string, limit = 200) {
  return all<{ id: number; user_id: string | null; user_name: string | null; action: string; entity_type: string; entity_id: string | null; details: string | null; created_at: string }>(
    `SELECT a.*, u.name AS user_name FROM audit_log a LEFT JOIN users u ON u.id = a.user_id
     WHERE a.org_id = ? ORDER BY a.id DESC LIMIT ?`, orgId, limit,
  ).map((r) => ({ ...r, details: r.details ? JSON.parse(r.details) : null }));
}
