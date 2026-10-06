# LedgerBank API reference

Base URL `http://localhost:4000` (the web app calls it as `/api/...` through the Next.js proxy).
Auth: `Authorization: Bearer <token>` — a session token from `POST /auth/login` or an API key (`lb_live_…`) from `POST /api-keys`.
All amounts are **integer cents**. Dates are `YYYY-MM-DD`; timestamps are ISO-8601 UTC.
Errors: `{ "error": "<code>", "message": "<human text>" }` with 400 / 401 / 403 / 404 / 409.

Roles: `admin` (everything), `bookkeeper` (money movement needs admin approval, books, AP/AR), `employee` (own cards, own transactions, reimbursements).

## Public
| Method | Path | Notes |
|---|---|---|
| GET | `/health` | `{ ok, now, ledger_balanced, seeded }` |
| POST | `/auth/login` | `{ email, password }` → `{ token }` |
| GET | `/public/invoices/:token` | Hosted invoice page data |
| POST | `/public/invoices/:token/pay` | `{ amount?, method: 'ach'\|'card' }` — simulates the customer paying |

## Organization & team
| GET | `/me` | `{ user, organization }` |
|---|---|---|
| POST | `/auth/logout` | |
| PATCH | `/organization` | admin · `{ name?, address?, approval_threshold? }` |
| GET/POST | `/team` | POST admin · `{ email, name, role, password? }` (default password `password123`) |
| PATCH | `/team/:id` | admin · `{ role?, status: 'active'\|'disabled' }` — disabling freezes cards |
| GET/POST | `/api-keys`, DELETE `/api-keys/:id` | admin · POST `{ name }` → `{ id, name, secret }` (secret shown once) |
| GET | `/audit` | admin · latest 200 audit events |
| GET | `/insights` | dashboard: cash, burn, runway, monthly cash flow, top spend, to-dos |

## Accounts & transactions
| GET | `/accounts` | each with `balance: { current, pending, available }` (credit: current is negative = owed) |
|---|---|---|
| POST | `/accounts` | admin · `{ name, type: checking\|savings\|treasury\|credit, apy_bps?, credit_limit? }` |
| GET/PATCH | `/accounts/:id` | PATCH admin · `{ name?, status? }` (close requires zero balance) |
| GET | `/accounts/:id/statements` | `['2026-09', ...]` |
| GET | `/accounts/:id/statements/:YYYY-MM` | `{ opening_balance, closing_balance, total_in, total_out, transactions }`; `?format=csv` |
| GET | `/transactions` | `?account_id&status&kind&card_id&direction=in\|out&search&from&to&uncategorized=true&limit&offset&format=csv` → `{ total, rows }` |
| GET/PATCH | `/transactions/:id` | PATCH `{ note?, receipt_name?, gl_account_id?, reconciled? }` — changing `gl_account_id` re-books it (409 if period closed) |

Transaction `status`: `pending | posted | failed | declined | reversed`. `kind`: `card, card_refund, ach_in, ach_out, same_day_ach_out, wire_in, wire_out, intl_wire_out, check_out, check_deposit, transfer_in, transfer_out, interest, cashback, ach_return, invoice_card`.

## Payments & recipients
| GET/POST | `/recipients` | `{ name, email?, type, ach_routing?, ach_account?, wire_routing?, wire_account?, swift?, iban?, country, address?, default_gl_account_id?, is_customer?, is_vendor? }` |
|---|---|---|
| PATCH/DELETE | `/recipients/:id` | |
| GET | `/payments` | `?status=` |
| POST | `/payments` | `{ account_id, rail, amount, counterparty_id? \| to_account_id?, memo?, scheduled_for?, gl_account_id? }`; header `Idempotency-Key` supported |
| POST | `/payments/:id/approve` | admin; ≥ threshold needs a different admin than the creator |
| POST | `/payments/:id/cancel` | pending_approval or scheduled only |

Rails & settlement (simulated): `book` instant · `wire` 15 min · `same_day_ach` 4 h · `ach` 1 business day · `intl_wire` 1 business day · `check` 5 business days.
Payment status: `pending_approval → scheduled → processing → completed`, or `failed | canceled | returned`.
Payments by non-admins, or ≥ the org approval threshold, require approval.

## Cards
| GET/POST | `/cards` | POST admin · `{ account_id, user_id, nickname, form: virtual\|physical, spend_limit?, limit_interval?: transaction\|daily\|monthly\|all_time, blocked_categories?: string[] }` |
|---|---|---|
| GET/PATCH | `/cards/:id` | PATCH `{ nickname?, status: active\|frozen\|canceled, spend_limit?, limit_interval?, blocked_categories? }` (cardholder may freeze/unfreeze) |
| POST | `/cards/:id/reveal` | `{ pan, cvv, exp_month, exp_year }` — audited |
| GET | `/merchant-categories` | list of categories usable in `blocked_categories` |

## Bill pay (AP)
| GET/POST | `/bills` | `{ vendor_id, invoice_number?, amount, issue_date, due_date, gl_account_id?, memo?, attachment_name?, submit? }` |
|---|---|---|
| GET/PATCH | `/bills/:id` | |
| POST | `/bills/:id/submit` · `/approve` (admin; books Dr expense / Cr AP) · `/void` | |
| POST | `/bills/:id/pay` | `{ account_id, rail, scheduled_for? }` → `{ bill, payment }` (defaults to the due date) |

Bill status: `draft → pending_approval → approved → scheduled → paid`, or `void`.

## Invoicing (AR)
| GET/POST | `/invoices` | `{ customer_id, issue_date, due_date, deposit_account_id, memo?, lines: [{ description, quantity, unit_price, gl_account_id? }], send? }` |
|---|---|---|
| GET | `/invoices/:id` | includes `lines`, `public_token`, `overdue` |
| POST | `/invoices/:id/send` (books Dr AR / Cr revenue) · `/void` | |

## Reimbursements
| GET/POST | `/reimbursements` | `{ merchant, amount, spent_on, gl_account_id?, description?, receipt_name? }` |
|---|---|---|
| POST | `/reimbursements/:id/approve` | admin · `{ account_id? }` → pays the employee by ACH |
| POST | `/reimbursements/:id/reject` | admin |

## Books (admin, bookkeeper)
| GET/POST | `/books/accounts` | chart of accounts with `balance` (natural sign), `debits`, `credits`; `?from&to` |
|---|---|---|
| PATCH | `/books/accounts/:id` | `{ name?, archived? }` |
| GET | `/books/accounts/:id/ledger` | general ledger detail with running balance |
| GET/POST | `/books/journal` | POST `{ date, memo, lines: [{ gl_account_id, debit, credit, description? }] }` (must balance) |
| DELETE | `/books/journal/:id` | manual entries only |
| GET | `/books/reports/pnl?from&to` · `/pnl-monthly?months=6` · `/balance-sheet?as_of` · `/trial-balance?as_of` · `/ar-aging` · `/ap-aging` | |
| GET/POST/DELETE | `/books/rules` | POST `{ match_field: counterparty\|description\|merchant_category, match_value, gl_account_id, priority?, apply_to_existing? }` |
| POST | `/books/auto-categorize` | re-runs rules on uncategorized transactions |
| GET | `/books/reconciliation` | per account: bank vs book balance, difference, unreconciled count |
| POST | `/books/reconciliation/:accountId` | `{ through }` marks posted transactions reconciled |
| POST | `/books/close` | admin · `{ through: date \| null }` locks the period |

## Simulator (admin) — the outside world
| GET | `/sim/clock` | |
|---|---|---|
| POST | `/sim/advance` | `{ hours }` moves the simulated clock and runs the back office |
| POST | `/sim/tick` | run settlement / captures / interest / month-end now |
| POST | `/sim/incoming` | `{ account_id, amount, rail: ach\|wire\|intl_wire\|check, counterparty_name, description? }` |
| POST | `/sim/cards/:id/authorize` | `{ merchant, amount, merchant_category?, mcc? }` → `{ approved, decline_reason, transaction_id }` |
| POST | `/sim/transactions/:id/capture` · `/void` · `/refund` | capture/refund take `{ amount? }` |
| POST | `/sim/payments/:id/return` | `{ reason? }` ACH return (R-code) |
