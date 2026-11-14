# Data

One PostgreSQL database. The schema is made by Flyway migrations. All access is plain SQL through Spring's `JdbcClient`; there is no ORM ([ADR-0006](../adr/0006-plain-sql.md)).

- Money columns are `bigint` halalas with `CHECK (>= 0)` where a negative makes no sense.
- Times are `timestamptz`, always UTC.
- Ids are `uuid`, made by Mizan.

## Tables

| Table | One row is | Key points |
|---|---|---|
| `api_keys` | A caller's key | Only the SHA-256 hash is stored. `revoked_at` |
| `payments` | A payment | `version`; `next_sync_at` with a partial index for the worker; `sync_requested`; `sync_failures`; unique `(gateway, gateway_payment_id)`; a partial index on the payments with money that no file has settled, for the daily check and the report |
| `operations` | A create, capture, cancel or refund | Unique partial index: one `IN_FLIGHT` per payment. `seq` keeps the order they were written in |
| `idempotency_keys` | A key and what it made | Primary key: the key. `request_hash`, the payment or operation id, `expires_at`. No answer is stored |
| `gateway_events` | A webhook that came in | Unique `(gateway, event_id)`. Only the id, the type and the gateway's payment id are kept, and Mizan's payment id when one matched; not the body |
| `ledger_entries` | A money fact | Unique `dedupe_key`. Never updated. `seq` keeps the order |
| `ledger_postings` | One side of an entry | `CHECK (amount > 0)`. Never updated |
| `settlement_files` | An imported file | Unique `(gateway, payout_id)`; `sha256`. Its progress is counted from its lines |
| `settlement_lines` | A line of a file | Index on `(gateway, transaction_id)`; `status`: `NEW`, `NEEDS_SYNC`, `MATCHED`, `SUSPENSE`, `ACCEPTED`; `next_attempt_at` while it waits; `seq` keeps the file order |
| `discrepancies` | A difference | One open per line; one open per `(payment, type)` for the types without a line; one per gateway payment id for unknown payments. `suspense_amount`; `seq` keeps the order |
| `outbox_events` | An event for the store | The body, exactly as it will be sent. `next_attempt_at` with a partial index for the sender. `seq` keeps the order |

## Rules the database enforces itself

| Rule | How |
|---|---|
| One operation in flight per payment | `UNIQUE (payment_id) WHERE status = 'IN_FLIGHT'` |
| An operation in flight has a call written down | `CHECK`: at least one attempt and its time |
| `0 ≤ refunded ≤ captured ≤ amount` | `CHECK` on `payments` |
| A payment is `CAPTURED` exactly when something was captured | `CHECK` on `payments` |
| `settled_captured ≤ captured_amount`, `settled_refunded ≤ refunded_amount` | `CHECK` on `payments` |
| A status, method, gateway or account is a known word | `CHECK` on each column |
| An entry balances, and has postings | A constraint trigger, checked at commit |
| The ledger is append-only | A trigger refuses `UPDATE`, `DELETE` and `TRUNCATE` |
| An entry is closed once written | A trigger refuses a posting that joins an entry of an earlier transaction, even a balanced pair |
| A ledger fact once | `UNIQUE (dedupe_key)` |
| An idempotency key points to one thing | `CHECK`: a payment or an operation, never both, never none |
| One open discrepancy of a type per payment, or per unknown gateway payment | Two partial unique indexes |
| A webhook event once | `UNIQUE (gateway, event_id)` |
| A payout once | `UNIQUE (gateway, payout_id)` |
| A line adds up | `CHECK (net = gross − fee)` |
| A line waits exactly while it is `NEW` or `NEEDS_SYNC` | `CHECK` on `next_attempt_at` |

The code checks the same rules first and gives a clear error. The database is the last line: a bug in the code cannot write a wrong row.

## Locks

| What | Lock |
|---|---|
| A request with an `Idempotency-Key` | The key is inserted first. A second request with the same key waits at that insert until the first transaction ends |
| Any change to a payment | First an advisory lock on the order (the `reference`), then `SELECT … FOR UPDATE` on the payment row. One helper does both, and it refuses to run outside a transaction |
| Workers picking rows | `FOR UPDATE SKIP LOCKED`, small batches |
| Lock order | Key, order, payment, its operation, ledger rows. Always this order, so two transactions cannot wait for each other |

The order's lock is a PostgreSQL advisory lock on a number made from the reference. It is held to the end of the transaction. Two references that give the same number only wait for each other; nothing breaks.

## Small things that matter

| Thing | Why |
|---|---|
| Times are cut to microseconds before they are used | That is what PostgreSQL keeps. A time read back is then equal to the time that was written |
| The foreign keys of `idempotency_keys` are checked at commit | The key is saved first, to stop a second request; what it points to is written right after, in the same transaction |
| An event's body is written once, into `outbox_events.payload` | What is sent later is exactly what was true when the change happened |

## Kept how long

| Data | Kept |
|---|---|
| Payments, operations, ledger, settlement files and lines, discrepancies | Forever (financial records) |
| Idempotency keys | 30 days |
| Webhook events, delivered store events | 90 days |

A daily job deletes what is past its time, in batches of 1,000, each in its own short transaction. An event that waits or failed is never deleted: it waits for a person.

## Tests

| Test | Proves |
|---|---|
| Migration test | The schema builds from nothing; a second run changes nothing |
| Constraint tests | Each row of the "database enforces" table, by trying to break it with plain SQL |
| Repository tests | Each query, against a real PostgreSQL in a container |
