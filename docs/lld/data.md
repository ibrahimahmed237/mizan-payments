# Data

One PostgreSQL database. The schema is made by Flyway migrations. All access is plain SQL through Spring's `JdbcClient`; there is no ORM ([ADR-0006](../adr/0006-plain-sql.md)).

- Money columns are `bigint` halalas with `CHECK (>= 0)` where a negative makes no sense.
- Times are `timestamptz`, always UTC.
- Ids are `uuid`, made by Mizan.

## Tables

| Table | One row is | Key points |
|---|---|---|
| `api_keys` | A caller's key | Only the SHA-256 hash is stored. `revoked_at` |
| `payments` | A payment | `version`; `next_sync_at` with a partial index for the worker; unique `(gateway, gateway_payment_id)` |
| `operations` | A create, capture, cancel or refund | Unique partial index: one `IN_FLIGHT` per payment |
| `idempotency_keys` | A key and what it made | Primary key: the key. `request_hash`, the payment or operation id, `expires_at`. No answer is stored |
| `gateway_events` | A webhook that came in | Unique `(gateway, event_id)`. Only the id, the type and the payment id are kept, not the body |
| `ledger_entries` | A money fact | Unique `dedupe_key`. Never updated |
| `ledger_postings` | One side of an entry | `CHECK (amount > 0)`. Never updated |
| `settlement_files` | An imported file | Unique `(gateway, payout_id)`; `sha256`; `status` |
| `settlement_lines` | A line of a file | Index on `(gateway, transaction_id)`; `status`: `NEW`, `NEEDS_SYNC`, `MATCHED`, `SUSPENSE` |
| `discrepancies` | A difference | One open per line; one open per `(payment, type)` for the types without a line; one per gateway payment id for unknown payments |
| `outbox_events` | An event for the store | `next_attempt_at` with a partial index for the sender |

## Rules the database enforces itself

| Rule | How |
|---|---|
| One operation in flight per payment | `UNIQUE (payment_id) WHERE status = 'IN_FLIGHT'` |
| `0 ≤ refunded ≤ captured ≤ amount` | `CHECK` on `payments` |
| `settled_captured ≤ captured_amount`, `settled_refunded ≤ refunded_amount` | `CHECK` on `payments` |
| An entry balances | A constraint trigger, checked at commit |
| The ledger is append-only | A trigger refuses `UPDATE` and `DELETE` |
| A ledger fact once | `UNIQUE (dedupe_key)` |
| A webhook event once | `UNIQUE (gateway, event_id)` |
| A payout once | `UNIQUE (gateway, payout_id)` |

The code checks the same rules first and gives a clear error. The database is the last line: a bug in the code cannot write a wrong row.

## Locks

| What | Lock |
|---|---|
| Any change to a payment | First an advisory lock on the order (the `reference`), then `SELECT … FOR UPDATE` on the payment row. One helper does both |
| Workers picking rows | `FOR UPDATE SKIP LOCKED`, small batches |
| Lock order | Order, payment, its operation, ledger rows. Always this order, so two transactions cannot wait for each other |

## Kept how long

| Data | Kept |
|---|---|
| Payments, operations, ledger, settlement files and lines, discrepancies | Forever (financial records) |
| Idempotency keys | 30 days |
| Webhook events, delivered store events | 90 days |

A daily job deletes what is past its time, in small batches.

## Tests

| Test | Proves |
|---|---|
| Migration test | The schema builds from nothing; a second run changes nothing |
| Constraint tests | Each row of the "database enforces" table, by trying to break it with plain SQL |
| Repository tests | Each query, against a real PostgreSQL in a container |
