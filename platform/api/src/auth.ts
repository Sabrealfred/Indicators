import { createHash, randomBytes, scryptSync, timingSafeEqual } from 'node:crypto';
import type { FastifyReply, FastifyRequest } from 'fastify';
import { AppError, newId, one, run } from './db.js';
import { addDays, nowIso } from './clock.js';
import type { Actor } from './services/banking.js';

export function hashPassword(pw: string) {
  const salt = randomBytes(16);
  return `scrypt:${salt.toString('hex')}:${scryptSync(pw, salt, 32).toString('hex')}`;
}

export function verifyPassword(pw: string, stored: string) {
  const [, salt, hash] = stored.split(':');
  const a = scryptSync(pw, Buffer.from(salt, 'hex'), 32);
  return timingSafeEqual(a, Buffer.from(hash, 'hex'));
}

export function login(email: string, password: string) {
  const u = one<{ id: string; password_hash: string; status: string }>('SELECT * FROM users WHERE email = ?', email.toLowerCase().trim());
  if (!u || u.status !== 'active' || !verifyPassword(password, u.password_hash)) throw new AppError(401, 'Invalid email or password', 'unauthorized');
  const token = randomBytes(32).toString('base64url');
  run('INSERT INTO sessions(token, user_id, expires_at) VALUES (?, ?, ?)', token, u.id, addDays(new Date(), 7).toISOString());
  return token;
}

export function logout(token: string) {
  run('DELETE FROM sessions WHERE token = ?', token);
}

const sha256 = (s: string) => createHash('sha256').update(s).digest('hex');

export function createApiKey(actor: Actor, name: string) {
  const secret = `lb_live_${randomBytes(24).toString('base64url')}`;
  const id = newId('key');
  run('INSERT INTO api_keys(id, org_id, user_id, name, key_hash, prefix, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
    id, actor.org_id, actor.id, name, sha256(secret), secret.slice(0, 12), nowIso());
  return { id, name, secret };
}

function actorFromToken(token: string): Actor | null {
  if (token.startsWith('lb_live_')) {
    return one<Actor>(
      `SELECT u.id, u.org_id, u.role, u.name FROM api_keys k JOIN users u ON u.id = k.user_id
       WHERE k.key_hash = ? AND k.revoked_at IS NULL AND u.status = 'active'`, sha256(token),
    ) ?? null;
  }
  return one<Actor>(
    `SELECT u.id, u.org_id, u.role, u.name FROM sessions s JOIN users u ON u.id = s.user_id
     WHERE s.token = ? AND s.expires_at > ? AND u.status = 'active'`, token, new Date().toISOString(),
  ) ?? null;
}

declare module 'fastify' {
  interface FastifyRequest {
    actor: Actor;
  }
}

export async function requireAuth(req: FastifyRequest, _reply: FastifyReply) {
  const header = req.headers.authorization ?? '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : '';
  const actor = token ? actorFromToken(token) : null;
  if (!actor) throw new AppError(401, 'Authentication required', 'unauthorized');
  req.actor = actor;
}
