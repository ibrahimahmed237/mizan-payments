# Review 004 — M3 and M4: payments, webhooks and the worker

- **Date:** 2026-11-14
- **Scope:** `mizan-app` up to M4: the schema, API keys, the gateway clients, create, capture, cancel, refund, idempotency, the ledger in the database, webhooks, the customer's return, the sync worker, expiry, late capture and follow-ups. Also the core rules added for follow-ups.
- **Method:** the [review checklist](../../CONTRIBUTING.md#review-checklist), read as a critic; then 209 deliberate bugs, each planted alone, with the tests that should catch it.

## Built

| Part | What it is |
|---|---|
| Layers | `adapter.in` (web, worker, command line), `application` (use cases and ports), `adapter.out` (database, gateways). A test keeps the application free of Spring web, JDBC, Jackson and HTTP clients |
| The schema | Plain SQL with Flyway. The database itself refuses a second operation in flight, a ledger entry that does not balance, a change to a posted entry, and more ([data](../lld/data.md#rules-the-database-enforces-itself)) |
| One step for every change | Lock the payment, write the operation `IN_FLIGHT`, commit; call the gateway with no transaction open; lock again and write the result. A crash between the two leaves an operation that the worker finishes |
| Webhooks | Read, checked (secret or signature), saved once, and turned into a request for a look. Nothing in them is trusted |
| The worker | Takes due payments with `FOR UPDATE SKIP LOCKED` and a lease of 30 seconds. One step per payment: fetch, apply, decide (timers, unknown results, follow-ups) |
| Integration tests | The real service, a real PostgreSQL in a container, and the simulator, on one clock that the tests move. After **every** test the books are checked: each entry balances, and each payment's accounts match what it holds |

## Findings

| # | Area | Finding | Fix |
|---|---|---|---|
| 1 | Race | When a payment of an order took money, the order's other waiting payments were canceled **without their row lock**. A webhook for one of them, landing in between, could have its request for a look overwritten, and that look would be lost. | Every payment that changes is locked first, siblings too. The lock order is fixed (order lock, then payment rows), so two steps cannot wait for each other. |
| 2 | Correctness | A create with no answer is ended as `gateway_unreachable` after the quiet minute, at a gateway where nothing can be paid without the answer. A step that found a gateway id meanwhile (a webhook gave it) still ended it. | The step decides only if the payment still has no id. Otherwise the next look asks the gateway. |
| 3 | Simplicity | A webhook's request for a look was taken down in **two** places: when a round took the payment, and when the step took it. Bug 167 removed the first one, and nothing changed. | One place: the step, just before its fetch. Fewer places to reason about. |
| 4 | Simplicity | A webhook set the next look to "now, unless it is earlier already". Bug 168 showed the difference is never seen: an earlier time is already due. | "Now". |
| 5 | Simplicity | Five checks that could never fail, each found by a bug that survived: a payment's own money excluded from "other money" (it holds none when the rule asks); an idempotency key without its payment or operation id (the request's hash already covers the path); a revoked key checked again after the filter refused it; an empty address prefix refused twice; a trigger against emptying the ledger's entries (the postings' foreign key already refuses it). | Removed. Each one now has a comment or a test that says why it is not needed. |
| 6 | Tests | Bugs 31, 148 and 159 survived: an operation started without setting the next look; a create without an id ended before the quiet minute; a step with nothing to ask that did not set the next look. The code was right; the tests did not look at the time of the next look. | `CrashIT` now checks the payment is still pending one second before the quiet minute ends, and that the next look is then one second away. |
| 7 | Tests | Bug 101 survived: a second open discrepancy of the same kind would have been an error, not a "nothing to do". | `SyncIT` syncs the same wrong record twice, and the second sync answers `200`. |
| 8 | Docs | The docs said "a command's look does not take the flag down". Since `POST /v1/payments/{id}/sync` runs the worker's step, it does. | [Workers](../lld/workers.md#a-request-for-a-look-is-never-lost) says: the step takes it down, for the worker and for `/sync`; the return page does not. |

## Experiments

209 deliberate bugs, each planted alone. The ones that survived at first are in the findings above. After the fixes, bugs 31, 101, 148 and 159 were planted again, with the new tests, and were caught. The others can no longer be planted: their code is gone.

| Area | Bugs | Caught at first | After the fixes |
|---|---|---|---|
| Payments: create, commands, idempotency, the writer, the ledger and the outbox in the database | 86 | 82 | 83, and 3 deleted |
| API keys, errors, settings | 18 | 17 | 17, and 1 deleted |
| The gateway clients | 20 | 20 | 20 |
| Webhooks and the customer's return | 30 | 29 | 29, and 1 deleted |
| The worker, sync and the schedule | 34 | 29 | 32, and 2 deleted |
| The schema | 12 | 10 | 10, 1 deleted, and 1 that changed nothing (see below) |
| Core: starting a follow-up | 9 | 9 | 9 |
| **All** | **209** | **196** | **200, 8 deleted, and 1 that changed nothing** |

Bug 198 changed an index of migration V2. Migration V3 drops that index and makes it again, so the bug changed nothing. It was planted again in V3, in [review 005](005-m5-m6-reconciliation-and-hardening.md).

## Checked and kept

| Topic | Why it holds |
|---|---|
| No gateway call inside a transaction (R5) | Each use case calls the gateway between two `transactions.run` blocks. A test keeps the gateway silent and checks, during the call, that no transaction is open and the payment's row is free |
| A webhook never calls the gateway | It only saves and asks for a look. So it cannot wait for the simulator that is sending it |
| The return page looks only if the last look is older than 2 seconds | A customer who reloads the page does not make a gateway call each time |
| The quiet minute | A call is cut off after 10 seconds; after a minute it has landed or died. Deciding earlier would call a slow success a failure |
| A card refund is never repeated by Mizan | The card gateway takes no refund key. The next sync finds a refund that happened after all, and counts it before any new refund |
| Two at once | `WorkerIT` runs two workers on the same payments; `RaceIT` sends requests together (the same key twice, a capture and a cancel, two refunds, two tabs, many looks). Nobody is charged twice; every step is written once |

## Result

M3 and M4 are done. Next: reconciliation (M5) and hardening (M6), in [review 005](005-m5-m6-reconciliation-and-hardening.md).
