// Shapes returned by the LedgerBank API. All amounts are integer cents.

export type Role = 'admin' | 'bookkeeper' | 'employee';

export interface Me {
  user: { id: string; org_id: string; email: string; name: string; role: Role };
  organization: { id: string; name: string; legal_name: string; ein: string; address: string; approval_threshold: number; books_locked_through: string | null };
}

export interface Balance { current: number; pending: number; available: number }

export interface Account {
  id: string;
  name: string;
  type: 'checking' | 'savings' | 'treasury' | 'credit';
  account_number: string;
  routing_number: string;
  apy_bps: number;
  credit_limit: number;
  status: 'open' | 'frozen' | 'closed';
  gl_account_id: string;
  created_at: string;
  balance: Balance;
}

export interface Transaction {
  id: string;
  account_id: string;
  account_name: string;
  amount: number;
  status: 'pending' | 'posted' | 'failed' | 'declined' | 'reversed';
  kind: string;
  counterparty_name: string;
  description: string;
  merchant_category: string | null;
  gl_account_id: string | null;
  category_name: string | null;
  category_code: string | null;
  note: string | null;
  receipt_name: string | null;
  reconciled: number;
  decline_reason: string | null;
  card_id: string | null;
  card_last4: string | null;
  cardholder: string | null;
  payment_id: string | null;
  transfer_group: string | null;
  created_at: string;
  posted_at: string | null;
}

export interface Recipient {
  id: string;
  name: string;
  email: string | null;
  type: 'business' | 'individual';
  ach_routing: string | null;
  ach_account: string | null;
  wire_routing: string | null;
  wire_account: string | null;
  swift: string | null;
  iban: string | null;
  country: string;
  address: string | null;
  default_gl_account_id: string | null;
  is_customer: number;
  is_vendor: number;
}

export interface Payment {
  id: string;
  account_id: string;
  account_name: string;
  counterparty_id: string | null;
  counterparty_name: string | null;
  to_account_id: string | null;
  to_account_name: string | null;
  rail: string;
  amount: number;
  memo: string | null;
  status: 'pending_approval' | 'scheduled' | 'processing' | 'completed' | 'failed' | 'canceled' | 'returned';
  scheduled_for: string | null;
  settle_at: string | null;
  failure_reason: string | null;
  created_by: string | null;
  created_by_name: string | null;
  approved_by_name: string | null;
  bill_id: string | null;
  reimbursement_id: string | null;
  created_at: string;
  completed_at: string | null;
}

export interface Card {
  id: string;
  account_id: string;
  user_id: string;
  nickname: string;
  form: 'virtual' | 'physical';
  network: string;
  last4: string;
  exp_month: number;
  exp_year: number;
  status: 'active' | 'frozen' | 'canceled';
  spend_limit: number | null;
  limit_interval: 'transaction' | 'daily' | 'monthly' | 'all_time' | null;
  blocked_categories: string[];
  spent_in_window: number;
  created_at: string;
}

export interface Bill {
  id: string;
  vendor_id: string;
  vendor_name: string;
  invoice_number: string | null;
  amount: number;
  issue_date: string;
  due_date: string;
  gl_account_id: string | null;
  memo: string | null;
  attachment_name: string | null;
  status: 'draft' | 'pending_approval' | 'approved' | 'scheduled' | 'paid' | 'void';
  payment_id: string | null;
  overdue: number;
}

export interface InvoiceLine { id: number; description: string; quantity: number; unit_price: number; amount: number; gl_account_id: string | null }

export interface Invoice {
  id: string;
  customer_id: string;
  customer_name: string;
  customer_email?: string | null;
  number: string;
  issue_date: string;
  due_date: string;
  deposit_account_id: string;
  status: 'draft' | 'sent' | 'paid' | 'void';
  memo: string | null;
  total: number;
  amount_paid: number;
  public_token: string;
  overdue: boolean;
  lines?: InvoiceLine[];
}

export interface Reimbursement {
  id: string;
  user_id: string;
  user_name: string;
  merchant: string;
  amount: number;
  spent_on: string;
  gl_account_id: string | null;
  description: string | null;
  receipt_name: string | null;
  status: 'submitted' | 'approved' | 'rejected' | 'paid';
  created_at: string;
}

export interface GlAccount {
  id: string;
  code: string;
  name: string;
  type: 'asset' | 'liability' | 'equity' | 'revenue' | 'expense';
  subtype: string | null;
  system_key: string | null;
  archived: number;
  balance: number;
  debits: number;
  credits: number;
}

export interface TeamMember { id: string; email: string; name: string; role: Role; status: string; active_cards: number; created_at: string }

export interface Insights {
  as_of: string;
  total_cash: number;
  credit_owed: number;
  accounts: { id: string; name: string; type: Account['type']; last4: string; balance: Balance; apy_bps: number; credit_limit: number }[];
  last_30_days: { money_in: number; money_out: number };
  monthly_cashflow: { month: string; money_in: number; money_out: number }[];
  avg_monthly_burn: number;
  runway_months: number | null;
  top_spend_categories: { name: string; amount: number }[];
  pending_approvals: number;
  bills_due_7d: { n: number; s: number };
  overdue_invoices: { n: number; s: number };
  reimbursements_to_review: number;
}
