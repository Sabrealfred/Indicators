# LedgerBank — business banking + books platform

A full-stack, Mercury-style financial operating system for startups, built from scratch under its own brand:
deposit accounts, payments on every rail, corporate cards, a corporate credit card with cashback, treasury
yield, bill pay, invoicing, reimbursements, and a complete double-entry bookkeeping suite — all running
against a **dummy database and a simulated outside world** (payment rails, card network, counterparties).

> Demo only. No real money moves. Going live requires a sponsor bank, a card issuing processor and a
> compliance program — see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#going-live).

## Run it

Requires Node 22+.

```bash
cd platform
npm install
npm run dev            # API on :4000 (auto-seeds 6 months of history), web on :3000
```

Open http://localhost:3000 and log in (password `demo1234` for everyone):

| User | Role | Use it to |
|---|---|---|
| demo@ledgerbank.dev | Admin | everything |
| marcus@acmerobotics.dev | Admin | second approver for payments over the threshold |
| priya@acmerobotics.dev | Bookkeeper | books, AP/AR; payments need admin approval |
| diego@acmerobotics.dev | Employee | own cards and reimbursements only |

Other commands: `npm test` (API test suite), `npm run reset --workspace api` (wipe and re-seed),
`docker compose up --build` (containerized).

## What's inside

| Module | Capabilities |
|---|---|
| **Accounts** | Checking, savings, treasury and credit accounts; account/routing numbers; current / pending / available balances; monthly statements (JSON + CSV) |
| **Transactions** | Unified feed with filters, search, CSV export, notes, receipts, categories, reconciliation flags |
| **Payments** | ACH, same-day ACH, domestic wire, international wire, mailed check, internal transfers; scheduling; idempotency keys; approval policy (threshold + dual control); ACH returns |
| **Recipients** | Vendors/customers/individuals with ACH, wire, SWIFT/IBAN and mailing details; default GL category |
| **Cards** | Virtual & physical cards (Luhn-valid PANs), per-transaction/daily/monthly/lifetime limits, blocked merchant categories, freeze, reveal (audited), authorization → capture → refund lifecycle |
| **IO-style credit** | Credit limit & utilization, 1.5% monthly cashback, autopay in full on the 1st |
| **Treasury** | Daily interest accrual on balance, paid monthly |
| **Bill pay (AP)** | Inbox → approval → scheduled payment → paid; accrual posting (expense/AP) |
| **Invoicing (AR)** | Line-item invoices, hosted public payment page, partial payments, overdue tracking; accrual posting (AR/revenue) |
| **Reimbursements** | Employee requests → admin approval → ACH to the employee |
| **Books** | Chart of accounts, auto-posting of every bank event, categorization rules, manual journal entries, P&L, balance sheet, trial balance, general ledger, AR/AP aging, bank-to-book reconciliation, period close |
| **Company** | Team & roles, API keys, audit log, approval threshold |
| **Simulator** | Advance the clock, inject incoming payments, run card purchases, capture/void/refund, return ACH |

API reference: [docs/API.md](docs/API.md). Architecture and the path to production: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Layout

```
platform/
  api/   Fastify + TypeScript, SQLite (node:sqlite) — ledger, books, services, routes, seed, tests
  web/   Next.js 16 + Tailwind 4 — app shell, 20+ screens, hosted invoice page
  docs/  API reference, architecture
```
