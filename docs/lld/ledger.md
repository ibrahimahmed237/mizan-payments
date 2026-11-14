# Ledger

The ledger is the store's book for money that moves through gateways. It is double-entry: every entry has debits and credits of the same total.

## Model

| Thing | Meaning |
|---|---|
| **Account** | A named bucket. Six of them, fixed |
| **Entry** | One money fact: its kind, its time, the payment or settlement line it belongs to, and a unique key |
| **Posting** | One side of an entry: account, debit or credit, amount in halalas (always above zero) |

| Account | Kind | Grows on | Meaning |
|---|---|---|---|
| `gateway_receivable` | Asset | Debit | Captured, not yet paid out by the gateway |
| `bank` | Asset | Debit | Paid out by the gateway |
| `gateway_fees` | Expense | Debit | Kept by the gateway |
| `sales` | Income | Credit | Paid by customers, minus refunds |
| `suspense` | Liability | Credit | Received and not explained yet |
| `adjustments` | Income or expense | Either | Differences finance accepted |

## Entries

| Kind | When | Debit | Credit | Key |
|---|---|---|---|---|
| `CAPTURE` | Sync: money taken | `gateway_receivable` | `sales` | `capture:{payment}:{captured total after}` |
| `REFUND` | Sync: money given back | `sales` | `gateway_receivable` | `refund:{payment}:{refunded total after}` |
| `SETTLE_CAPTURE` | A capture line matched | `bank` (net), `gateway_fees` (fee) | `gateway_receivable` (gross) | `line:{line}` |
| `SETTLE_REFUND` | A refund line matched | `gateway_receivable` (amount) | `bank` (amount) | `line:{line}` |
| `SUSPENSE_IN` | A line that did not match, money in | `bank` (net) | `suspense` (net) | `line:{line}` |
| `SUSPENSE_OUT` | A line that did not match, money out | `suspense` (net) | `bank` (net) | `line:{line}` |
| `REMATCH` | A suspense line matched later. The bank side was posted already | Capture line: `suspense` (net), `gateway_fees` (fee). Refund line: `gateway_receivable` | Capture line: `gateway_receivable` (gross). Refund line: `suspense` | `rematch:{line}` |
| `ACCEPT` | Finance accepted a difference | Money in: `suspense`. Money out: `adjustments` | Money in: `adjustments`. Money out: `suspense` | `accept:{discrepancy}` |

A fee that the gateway returns on a refund line (a negative fee) credits `gateway_fees`.

A line that did not match posts only what reached the bank (the net). Mizan cannot say what the fee of an unexplained line was for; the fee is posted when the line is explained. An entry whose amounts are all zero is not written.

**The key makes an entry happen once.** The key is unique in the database. If the same fact is recorded again (a retry, a second worker), the insert finds the key and does nothing. The key of a capture uses the total *after* the capture, so two different captures have two keys and the same capture has one.

## Rules

| # | Rule | Enforced by |
|---|---|---|
| L1 | An entry's debits equal its credits | The code that builds entries, and a database trigger that checks at commit |
| L2 | A posting's amount is above zero | A check constraint |
| L3 | Entries and postings are never changed or deleted | A database trigger |
| L4 | An entry is written in the same transaction as the fact it records (rule R6) | The use cases |
| L5 | An entry is written at most once | The unique key |

## What must always be true

| # | Invariant | Checked by |
|---|---|---|
| I1 | Total debits = total credits | The trial balance, in every test and in the proof run |
| I2 | For each payment: receivable = captured − refunded − settled capture + settled refund. It can be below zero for a while (a refund the gateway has not taken out of a payout yet) | A query used by tests and by the reconciliation report |
| I3 | `sales` = total captured − total refunded | The same |
| I4 | `suspense` = the sum of the net amounts of the open suspense lines | The same |
| I5 | When every capture and refund is settled and no discrepancy is open: `gateway_receivable = 0` and `suspense = 0` | The proof run |

## Reading

| Endpoint | Gives |
|---|---|
| `GET /v1/ledger/balances` | Each account's balance, and the trial balance |
| `GET /v1/ledger/entries?payment_id=` | The entries of one payment, oldest first |
| `GET /v1/reports/reconciliation` | Unsettled receivable by age, suspense, open discrepancies by type |

## Tests

| Test | Proves |
|---|---|
| Entry builder tests | Each kind balances; amounts and accounts are right |
| Property test: any list of facts | I1 holds after each; the same fact twice changes nothing |
| Database tests | The trigger refuses an unbalanced entry, an update and a delete; the key refuses a repeat |
| Flow tests | After each payment flow, I2 and I3 hold |
