# Review 007 — a second look at the finished project

- **Date:** 2026-11-14
- **Scope:** everything, once more, after [review 006](006-final-review.md) called the project ready. This round asked other questions than the earlier ones: what happens at the load the design names, what an outsider can do to the service, and what a person in finance cannot do.
- **Method:** first the whole build four times from a fresh clone, to find tests that fail now and then (none did). Then the code was read again, path by path. Each finding got a fix and a test that fails without the fix; 14 deliberate bugs were planted in the new code.

## Findings

| # | Area | Finding | Fix |
|---|---|---|---|
| 1 | Capacity | The sync worker looked at **one payment at a time**, and the event sender sent one event at a time. Each piece is mostly waiting for a gateway or for the store. The design names 50 payments a second at the peak; with a gateway that answers in a second, the worker could do one a second, and would have fallen behind for good. All five workers also shared **one scheduler thread**, so a slow gateway also stopped matching and events. | A round's pieces run side by side: 16 payments and 8 events at a time by default, both settings. Rule R5 makes this cheap: no transaction and no database connection is held while a piece waits. Each worker has a thread of its own. See [workers](../lld/workers.md#how-much-one-instance-can-do). |
| 2 | Correctness | After a clear "no" from the gateway, Mizan fetches before it writes the "no" down. If that fetch failed, the "no" was written all the same. For a call that was **sent again**, the "no" may only mean that the first call landed after all ("already captured"). The operation was then marked failed, and the store told so, although the money had moved. The payment itself was right (the next sync fixed it); the operation's record and one event were wrong. | A "no" to a repeated call, without the gateway's record, is not written down. The operation stays in flight and the worker decides on fresh facts. |
| 3 | Security | The webhook counter took its `gateway` tag from the address. Anyone can post to `/v1/webhooks/<anything>`, and each new name made a counter that stays in memory for good. The rate limit had one allowance per address too, so made-up names were never limited together. | An unknown name is answered `404` before anything is counted, and all unknown names share one limit. |
| 4 | Operations | The return page is limited per address. Behind a load balancer, where Mizan is meant to run, every request comes from the balancer's address: all customers shared one allowance of 20 a second, and at a busy hour customers coming back from the bank would have got `429`. | The customer's address is taken from `X-Forwarded-For`, only when the request itself comes from a private address. |
| 5 | Speed | The reconciliation report read every unsettled payment and every open discrepancy into memory and added them up in Java. The daily check and the report both ask for payments with money no file has settled, and no index held those, so both read every payment. | The database does the sums. A partial index holds exactly the unsettled payments; a test runs the real queries and checks the index was used. |
| 6 | Speed | A metrics scrape asked eight numbers about payments, each one a count over the whole payments table. | One grouped read answers all eight, and is kept for a second. |
| 7 | Gap | The list of discrepancies held the newest 100 and no more. The 10,000-order proof run opens over a thousand: finance could not have reached the rest. | Pages with a cursor, like the list of payments. The lists of files and events keep their cap, and the docs say so. |
| 8 | Robustness | A settlement file that starts with a byte order mark, as spreadsheet tools write it, was refused as malformed. | The mark is skipped, at the very start only. |
| 9 | Tests | Three things had a test that looked without checking: the report's age groups (only that a field was there), the gauges about payments (only "at least 1"), and the count of open discrepancies (a bug that also counted resolved ones got past). | Each now has a test of its exact numbers. |

## Experiments

14 deliberate bugs in the code of this round. Two got past the tests at first; both led to a stronger test (finding 9), and were caught after it.

| Area | Bugs | Caught at first | After the fixes |
|---|---|---|---|
| A "no" to a repeated call | 1 | 1 | 1 |
| Webhook names | 1 | 1 | 1 |
| Side-by-side rounds and the scheduler's threads | 4 | 4 | 4 |
| The report and the index | 4 | 3 | 4 |
| Pages of discrepancies | 1 | 1 | 1 |
| Gauges | 2 | 1 | 2 |
| The customer's address behind a balancer | 1 | 1 | 1 |
| **All** | **14** | **12** | **14** |

## Does it still hold with rounds side by side?

Running pieces side by side is the kind of change that breaks things quietly, so it was checked three ways:

| Check | Result |
|---|---|
| Unit test: two steps that each wait for the other to start | Pass only when they really run together |
| `WorkerIT` and `EventSenderIT`: a gateway and a store that take 200 ms | The calls overlap; each payment is captured once, each event delivered once |
| The **whole test suite** with every round run 8 at a time (`-Dmizan.test.parallel=8`) | Every test passed |
| A [proof run of 10,000 orders](../proof/run-10000-seed-23-side-by-side.md) with rounds 8 at a time | 10,306 payments, 6,188 faults: every check passed |

What keeps it safe is what was there before: every change to a payment locks the order, then the payment; an operation is written down, with its call counted, before the call leaves; and a look is safe to repeat.

## Checked and kept

| Topic | Why it stays |
|---|---|
| The matching worker handles lines one after another | A line is one short transaction. Only a line that does not fit calls the gateway. 200,000 lines take minutes, once per payout |
| The list of payments asks for each payment's operation in flight on its own | At most 100 small indexed reads per page. Simple, and fast enough |
| `GET /v1/ledger/balances` adds up every posting | Finance asks for it a few times a day. A running total would be one more thing that can drift from the entries |
| A pay-later webhook can give Mizan the provider's id of a payment whose create answer was lost | Only for a payment that has no id yet, and only with the provider's signature. The amount in the provider's record must still equal the payment's, or it is a discrepancy |
| `retry` and `accept` take no `Idempotency-Key` | A repeat finds the discrepancy resolved and gets `409`. Nothing happens twice |

## Result

Nine findings, all fixed. None of them lost or doubled money; two would have hurt at the design's own load (1 and 4), one was a wrong record with the right money (2), and one was a way for an outsider to waste memory (3). The open item is unchanged: the run against a real gateway's test mode.
