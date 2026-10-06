import { DatabaseSync } from 'node:sqlite';
import { randomBytes } from 'node:crypto';

// Single embedded database. Every amount is stored as integer cents.
// The schema is written so that swapping to PostgreSQL is a driver change, not a redesign.

const SCHEMA = `
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);

CREATE TABLE IF NOT EXISTS organizations (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  legal_name TEXT NOT NULL,
  ein TEXT NOT NULL,
  address TEXT NOT NULL,
  approval_threshold INTEGER NOT NULL DEFAULT 1000000,
  books_locked_through TEXT,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  org_id TEXT NOT NULL REFERENCES organizations(id),
  email TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('admin','bookkeeper','employee')),
  password_hash TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','invited','disabled')),
  counterparty_id TEXT,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
  token TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  expires_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS api_keys (
  id TEXT PRIMARY KEY,
  org_id TEXT NOT NULL REFERENCES organizations(id),
  user_id TEXT NOT NULL REFERENCES users(id),
  name TEXT NOT NULL,
  key_hash TEXT NOT NULL UNIQUE,
  prefix TEXT NOT NULL,
  created_at TEXT NOT NULL,
  revoked_at TEXT
);

-- Customer-facing bank accounts.
CREATE TABLE IF NOT EXISTS accounts (
  id TEXT PRIMARY KEY,
  org_id TEXT NOT NULL REFERENCES organizations(id),
  name TEXT NOT NULL,
  type TEXT NOT NULL CHECK (type IN ('checking','savings','treasury','credit')),
  account_number TEXT NOT NULL UNIQUE,
  routing_number TEXT NOT NULL,
  apy_bps INTEGER NOT NULL DEFAULT 0,
  credit_limit INTEGER NOT NULL DEFAULT 0,
  accrued_interest_micros INTEGER NOT NULL DEFAULT 0,
  last_accrual_date TEXT,
  status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open','frozen','closed')),
  ledger_account_id TEXT NOT NULL UNIQUE,
  gl_account_id TEXT,
  created_at TEXT NOT NULL
);

-- Bank core ledger: immutable double-entry. Sum of postings per entry is always zero.
CREATE TABLE IF NOT EXISTS ledger_accounts (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('customer','system'))
);
CREATE TABLE IF NOT EXISTS ledger_entries (
  id TEXT PRIMARY KEY,
  description TEXT NOT NULL,
  idempotency_key TEXT UNIQUE,
  posted_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS ledger_postings (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  entry_id TEXT NOT NULL REFERENCES ledger_entries(id),
  ledger_account_id TEXT NOT NULL REFERENCES ledger_accounts(id),
  amount INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_postings_account ON ledger_postings(ledger_account_id);

-- Funds reserved for pending card authorizations and outgoing payments.
CREATE TABLE IF NOT EXISTS holds (
  id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL REFERENCES accounts(id),
  amount INTEGER NOT NULL CHECK (amount > 0),
  reason TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','released')),
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS counterparties (
  id TEXT PRIMARY KEY,
  org_id TEXT NOT NULL REFERENCES organizations(id),
  name TEXT NOT NULL,
  email TEXT,
  type TEXT NOT NULL DEFAULT 'business' CHECK (type IN ('business','individual')),
  ach_routing TEXT,
  ach_account TEXT,
  wire_routing TEXT,
  wire_account TEXT,
  swift TEXT,
  iban TEXT,
  country TEXT NOT NULL DEFAULT 'US',
  address TEXT,
  default_gl_account_id TEXT,
  is_customer INTEGER NOT NULL DEFAULT 0,
  is_vendor INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS cards (
  id TEXT PRIMARY KEY,
  org_id TEXT NOT NULL REFERENCES organizations(id),
  account_id TEXT NOT NULL REFERENCES accounts(id),
  user_id TEXT NOT NULL REFERENCES users(id),
  nickname TEXT NOT NULL,
  form TEXT NOT NULL CHECK (form IN ('virtual','physical')),
  network TEXT NOT NULL DEFAULT 'mastercard',
  pan TEXT NOT NULL UNIQUE,
  last4 TEXT NOT NULL,
  exp_month INTEGER NOT NULL,
  exp_year INTEGER NOT NULL,
  cvv TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','frozen','canceled')),
  spend_limit INTEGER,
  limit_interval TEXT CHECK (limit_interval IN ('transaction','daily','monthly','all_time')),
  blocked_categories TEXT NOT NULL DEFAULT '[]',
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS payments (
  id TEXT PRIMARY KEY,
  org_id TEXT NOT NULL REFERENCES organizations(id),
  account_id TEXT NOT NULL REFERENCES accounts(id),
  counterparty_id TEXT REFERENCES counterparties(id),
  to_account_id TEXT REFERENCES accounts(id),
  rail TEXT NOT NULL CHECK (rail IN ('ach','same_day_ach','wire','intl_wire','book','check')),
  direction TEXT NOT NULL CHECK (direction IN ('outgoing','incoming')),
  amount INTEGER NOT NULL CHECK (amount > 0),
  memo TEXT,
  status TEXT NOT NULL CHECK (status IN ('pending_approval','scheduled','processing','completed','failed','canceled','returned')),
  scheduled_for TEXT,
  settle_at TEXT,
  failure_reason TEXT,
  created_by TEXT,
  approved_by TEXT,
  hold_id TEXT,
  bill_id TEXT,
  reimbursement_id TEXT,
  invoice_id TEXT,
  gl_account_id TEXT,
  idempotency_key TEXT UNIQUE,
  created_at TEXT NOT NULL,
  completed_at TEXT
);

-- Customer-facing transaction feed.
CREATE TABLE IF NOT EXISTS transactions (
  id TEXT PRIMARY KEY,
  org_id TEXT NOT NULL REFERENCES organizations(id),
  account_id TEXT NOT NULL REFERENCES accounts(id),
  amount INTEGER NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('pending','posted','failed','declined','reversed')),
  kind TEXT NOT NULL,
  counterparty_name TEXT NOT NULL,
  description TEXT NOT NULL,
  mcc TEXT,
  merchant_category TEXT,
  gl_account_id TEXT,
  note TEXT,
  receipt_name TEXT,
  reconciled INTEGER NOT NULL DEFAULT 0,
  decline_reason TEXT,
  card_id TEXT REFERENCES cards(id),
  payment_id TEXT REFERENCES payments(id),
  hold_id TEXT,
  ledger_entry_id TEXT,
  transfer_group TEXT,
  created_at TEXT NOT NULL,
  posted_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_tx_account ON transactions(account_id, created_at);
CREATE INDEX IF NOT EXISTS idx_tx_org ON transactions(org_id, created_at);

-- Accounts payable.
CREATE TABLE IF NOT EXISTS bills (
  id TEXT PRIMARY KEY,
  org_id TEXT NOT NULL REFERENCES organizations(id),
  vendor_id TEXT NOT NULL REFERENCES counterparties(id),
  invoice_number TEXT,
  amount INTEGER NOT NULL CHECK (amount > 0),
  issue_date TEXT NOT NULL,
  due_date TEXT NOT NULL,
  gl_account_id TEXT,
  memo TEXT,
  attachment_name TEXT,
  status TEXT NOT NULL CHECK (status IN ('draft','pending_approval','approved','scheduled','paid','void')),
  payment_id TEXT,
  created_by TEXT,
  approved_by TEXT,
  created_at TEXT NOT NULL
);

-- Accounts receivable.
CREATE TABLE IF NOT EXISTS invoices (
  id TEXT PRIMARY KEY,
  org_id TEXT NOT NULL REFERENCES organizations(id),
  customer_id TEXT NOT NULL REFERENCES counterparties(id),
  number TEXT NOT NULL,
  issue_date TEXT NOT NULL,
  due_date TEXT NOT NULL,
  deposit_account_id TEXT NOT NULL REFERENCES accounts(id),
  status TEXT NOT NULL CHECK (status IN ('draft','sent','paid','void')),
  memo TEXT,
  total INTEGER NOT NULL DEFAULT 0,
  amount_paid INTEGER NOT NULL DEFAULT 0,
  public_token TEXT NOT NULL UNIQUE,
  sent_at TEXT,
  paid_at TEXT,
  created_at TEXT NOT NULL,
  UNIQUE (org_id, number)
);
CREATE TABLE IF NOT EXISTS invoice_lines (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  invoice_id TEXT NOT NULL REFERENCES invoices(id),
  description TEXT NOT NULL,
  quantity REAL NOT NULL,
  unit_price INTEGER NOT NULL,
  amount INTEGER NOT NULL,
  gl_account_id TEXT
);

CREATE TABLE IF NOT EXISTS reimbursements (
  id TEXT PRIMARY KEY,
  org_id TEXT NOT NULL REFERENCES organizations(id),
  user_id TEXT NOT NULL REFERENCES users(id),
  merchant TEXT NOT NULL,
  amount INTEGER NOT NULL CHECK (amount > 0),
  spent_on TEXT NOT NULL,
  gl_account_id TEXT,
  description TEXT,
  receipt_name TEXT,
  status TEXT NOT NULL CHECK (status IN ('submitted','approved','rejected','paid')),
  reviewed_by TEXT,
  payment_id TEXT,
  created_at TEXT NOT NULL
);

-- Books: the company's own general ledger (accrual, double-entry, debit positive).
CREATE TABLE IF NOT EXISTS gl_accounts (
  id TEXT PRIMARY KEY,
  org_id TEXT NOT NULL REFERENCES organizations(id),
  code TEXT NOT NULL,
  name TEXT NOT NULL,
  type TEXT NOT NULL CHECK (type IN ('asset','liability','equity','revenue','expense')),
  subtype TEXT,
  system_key TEXT,
  archived INTEGER NOT NULL DEFAULT 0,
  UNIQUE (org_id, code)
);
CREATE TABLE IF NOT EXISTS journal_entries (
  id TEXT PRIMARY KEY,
  org_id TEXT NOT NULL REFERENCES organizations(id),
  date TEXT NOT NULL,
  memo TEXT NOT NULL,
  source_type TEXT NOT NULL,
  source_id TEXT,
  created_by TEXT,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_je_source ON journal_entries(source_type, source_id);
CREATE TABLE IF NOT EXISTS journal_lines (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  entry_id TEXT NOT NULL REFERENCES journal_entries(id) ON DELETE CASCADE,
  gl_account_id TEXT NOT NULL REFERENCES gl_accounts(id),
  amount INTEGER NOT NULL,
  description TEXT
);
CREATE INDEX IF NOT EXISTS idx_jl_account ON journal_lines(gl_account_id);

CREATE TABLE IF NOT EXISTS categorization_rules (
  id TEXT PRIMARY KEY,
  org_id TEXT NOT NULL REFERENCES organizations(id),
  match_field TEXT NOT NULL CHECK (match_field IN ('counterparty','description','merchant_category')),
  match_value TEXT NOT NULL,
  gl_account_id TEXT NOT NULL REFERENCES gl_accounts(id),
  priority INTEGER NOT NULL DEFAULT 100,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  org_id TEXT NOT NULL,
  user_id TEXT,
  action TEXT NOT NULL,
  entity_type TEXT NOT NULL,
  entity_id TEXT,
  details TEXT,
  created_at TEXT NOT NULL
);
`;

export type DB = DatabaseSync;

let instance: DatabaseSync | null = null;

export function openDb(path = process.env.DB_PATH ?? 'ledgerbank.db'): DatabaseSync {
  const db = new DatabaseSync(path);
  db.exec('PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON;');
  db.exec(SCHEMA);
  return db;
}

export function db(): DatabaseSync {
  if (!instance) instance = openDb();
  return instance;
}

export function setDb(d: DatabaseSync) {
  instance = d;
}

let txDepth = 0;
/** Runs fn atomically. Nested calls join the outer transaction. */
export function tx<T>(fn: () => T): T {
  const d = db();
  if (txDepth > 0) {
    txDepth++;
    try {
      return fn();
    } finally {
      txDepth--;
    }
  }
  d.exec('BEGIN IMMEDIATE');
  txDepth = 1;
  try {
    const out = fn();
    d.exec('COMMIT');
    return out;
  } catch (e) {
    d.exec('ROLLBACK');
    throw e;
  } finally {
    txDepth = 0;
  }
}

type Param = string | number | bigint | null | Uint8Array;
export const one = <T>(sql: string, ...p: Param[]) => db().prepare(sql).get(...p) as T | undefined;
export const all = <T>(sql: string, ...p: Param[]) => db().prepare(sql).all(...p) as T[];
export const run = (sql: string, ...p: Param[]) => db().prepare(sql).run(...p);

export function newId(prefix: string): string {
  return `${prefix}_${randomBytes(9).toString('base64url')}`;
}

export class AppError extends Error {
  constructor(public status: number, message: string, public code = 'error') {
    super(message);
  }
}
export const notFound = (what: string) => new AppError(404, `${what} not found`, 'not_found');
export const bad = (msg: string, code = 'invalid_request') => new AppError(400, msg, code);
export const forbidden = (msg = 'Forbidden') => new AppError(403, msg, 'forbidden');
