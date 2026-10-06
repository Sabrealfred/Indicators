# Architecture

## Two ledgers, on purpose

LedgerBank keeps money in two separate double-entry ledgers, the same split a real bank and its
customer have:

1. **Bank core ledger** (`ledger_accounts`, `ledger_entries`, `ledger_postings`) — what the bank owes
   the customer. Immutable postings; every entry sums to zero; balances are always computed, never
   stored. System accounts (`ach_clearing`, `wire_clearing`, `card_settlement`, `interest_expense`,
   `rewards_expense`, ...) stand in for the outside world. `GET /health` reports whether the whole
   ledger sums to zero.
2. **Books** (`gl_accounts`, `journal_entries`, `journal_lines`) — the company's own accrual general
   ledger (debits positive, credits negative). Each bank account has its own GL cash account (or a
   liability account for the credit card). Every settled bank transaction is posted automatically:
   cash against a category chosen by explicit tag → rules → recipient default → merchant category →
   transaction kind → "Uncategorized".

Reconciliation compares the two: bank balance per account vs its GL account. In the seeded data the
difference is zero for every account, and the test suite enforces it.

### Accrual flows

| Event | Books entry |
|---|---|
| Bill approved | Dr expense / Cr Accounts Payable |
| Bill paid | Dr Accounts Payable / Cr cash |
| Invoice sent | Dr Accounts Receivable / Cr revenue (per line) |
| Invoice paid | Dr cash / Cr Accounts Receivable |
| Card purchase settles | Dr expense / Cr credit card liability (or cash for debit) |
| Credit card autopay | Dr credit card liability / Cr cash |
| Interest / cashback | Dr cash / Cr interest income / cashback rewards |
| Funding round | Dr cash / Cr Common Stock & APIC |

Closed periods (`books_locked_through`) refuse any change that would alter an entry dated on or
before the lock date (HTTP 409).

## Money movement state machines

```
Payment:  pending_approval ─approve→ scheduled ─(date reached, funds held)→ processing ─(settle_at)→ completed
              │                         │                                      │                    │
              └─cancel→ canceled ←──────┘                       insufficient funds→ failed      ACH return→ returned

Card:     authorize ─(hold)→ pending ─capture (≤1 day, amount may change)→ posted ─refund→ card_refund
              └→ declined (frozen, expired, limit, blocked category, insufficient funds)
                           pending ─void→ reversed
```

Available balance = ledger balance − active holds (credit: limit + balance − holds). Holds are placed
when a payment starts processing or a card is authorized, and released on settlement.

## Back office

`services/processor.ts#tick` runs every 10 s in the server and on demand from the simulator:
release due scheduled payments → settle processing payments whose `settle_at` has passed → capture
card authorizations older than a day → accrue interest daily and pay it monthly → credit card month-end
(cashback + autopay). The clock is real time plus an offset (`/sim/advance`), so a week of
settlement can be exercised in a second. The seed drives this same code day by day over six months,
so the demo history is produced by the real engine, not inserted rows.

## Security model (demo level)

- Session tokens (random 256-bit, 7-day expiry) and API keys (`lb_live_…`, stored as SHA-256).
- scrypt password hashing.
- Role checks in services and routes: `admin`, `bookkeeper`, `employee`.
- Dual control: payments at or above the threshold need a different admin than the creator.
- Full PAN/CVV are only returned by `/cards/:id/reveal`, which is audited; listings never include them.
- Audit log of every state change.

## Going live

The simulator boundary is where real providers plug in. What it takes, roughly in order:

1. **Sponsor bank / BaaS** — Column, Increase, Unit, Treasury Prime or similar. Implement a
   `BankAdapter` behind `createPayment`/`processPayments`/`receiveFunds` that calls the partner API and
   moves settlement to webhooks instead of `settle_at`. Keep this ledger as the system of record and
   reconcile daily against the partner's ledger.
2. **Card issuing** — Lithic, Marqeta or Stripe Issuing. Replace `/sim/cards/:id/authorize` with the
   processor's real-time authorization webhook (the decision logic in `services/cards.ts#authorize` is
   already shaped for it; most processors require a reply in under ~2 seconds). Never store PANs yourself:
   use the processor's vault and PCI-scoped iframe for reveal.
3. **Onboarding (KYC/KYB)** — Persona, Alloy, Middesk; OFAC screening on customers and recipients.
4. **Compliance program** — BSA/AML officer and transaction monitoring, Reg E error resolution for
   consumer-like flows, UDAAP-reviewed marketing ("banking services provided by <partner bank>, Member FDIC"),
   SOC 2 Type II, PCI DSS for card data scope.
5. **Treasury** — real yield needs a registered investment adviser and a money-market sweep through a
   broker; it is not a deposit and is not FDIC insured.
6. **Credit card** — underwriting, a charge-card program with the issuing bank, and statement/billing
   disclosures.
7. **Infrastructure** — move from SQLite to PostgreSQL (the schema is portable; replace `node:sqlite`
   with `pg` and `BEGIN IMMEDIATE` with `SERIALIZABLE` transactions), run the processor as a separate
   worker with a job queue, add secrets management, encryption at rest, and per-tenant rate limits.
