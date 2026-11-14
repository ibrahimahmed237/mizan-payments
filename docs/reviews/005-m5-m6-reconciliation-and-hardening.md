# Review 005 — M5 and M6: reconciliation, events and hardening

- **Date:** 2026-11-14
- **Scope:** M5: settlement files, matching, suspense, discrepancies, finance's retry and accept, the daily check. M6: the outbox and the event sender, limits, logs, metrics, the API description, Docker, the clean-up.
- **Method:** the [review checklist](../../CONTRIBUTING.md#review-checklist), read as a critic, line by line; then 76 deliberate bugs, each planted alone, with the tests that should catch them.

## Built

| Part | What it is |
|---|---|
| Import | `POST /v1/settlements` takes the file as text. It is checked as a whole (header, line count, total) and refused as a whole. The same file again is answered as the first time; the same payout with other content is refused |
| Matching | A worker takes waiting lines in batches, with a lease. A line that does not fit gets one sync of its payment first, then becomes a discrepancy with its money in suspense |
| Finance | Lists, retry (sync and match again), accept with a note. Reports: balances and reconciliation per file |
| The daily check | Money captured or refunded more than 3 days ago that no file settled |
| Events | An outbox row in the same transaction as the change. Signed with HMAC-SHA256; retried after 1 and 5 minutes, 30 minutes, 2, 6 and 24 hours; then `FAILED`, and finance can send it again |
| Hardening | Limits per key, per gateway and per address; body limits; metrics; health; the API description served and checked against the routes; Docker Compose; a daily clean-up |

## Findings

| # | Area | Finding | Fix |
|---|---|---|---|
| 1 | Correctness | The daily check ran as **one long transaction without locks**. A line matched at the same moment could make it open a false "missing" discrepancy and tell the store. Its second half closed discrepancies settled since; if finance accepted one while the check ran, that close failed and rolled back every change of the run. And bug 42 showed the second half was never needed: only a matched line lowers what is missing, and matching closes the discrepancy itself. | One short transaction per payment, under the payment's lock. The second half is gone. |
| 2 | Speed | Each matched line looked for an open "missing" discrepancy by reading **all** open ones and filtering in Java. With many open ones, a large file would be slow. | One query by payment and type, which an index serves. It also locks the row, so finance's accept and the close cannot both win. |
| 3 | Correctness | The event sender took 50 events with a lease of **one minute**, but a round can take up to 500 seconds (50 calls, each cut off after 10 seconds). With two instances and a slow store, the second could send the same events at the same time. The comment claimed the lease was "longer than a call", which was true and beside the point. | The lease is ten minutes, longer than a round. Delivery stays at least once, and the docs say the store must ignore an event id it has seen. |
| 4 | Security | The 64 KB body limit was checked on the `Content-Length` header only. A body sent in pieces (`chunked`) has no such header, and Jackson would have read it whole into memory. | Such a body is read in the filter up to 64 KB, then refused with `413`. A test sends both kinds. |
| 5 | Tests | The store's time limit was fixed at 10 seconds and never tested. A slow store was a line of the plan with no test. | The store call uses `mizan.http.timeout`, like the gateways. A test with a store slower than the limit: the try fails, the event is sent again, and then delivered. |
| 6 | Docs | The design said a settlement file is "read as a stream". The parser reads line by line, but the body is first read into memory (at most 50 MB), because its hash is needed. Nothing tested a large file. | The docs say what happens. A test imports a file of **200,000 lines** in one call. It took 14 seconds; the JDBC driver now sends a batch of inserts as a few statements (`reWriteBatchedInserts`), and it takes about 9. |
| 7 | Simplicity | `settlement_lines` had two indexes on `(file_id, line_number)`: the unique constraint's, and a second one. | The second is gone. |
| 8 | Simplicity | Matching checked "a payment id is known" before syncing, but a line is only sent to sync when its payment was found. | The check is gone; the method returns the payment to sync, or nothing. |
| 9 | Tests | A test of the matching worker failed now and then. It expected the claimed lines in file order, but `UPDATE ... RETURNING` gives rows in no fixed order. Nothing depends on that order (a duplicate is decided by the file order in the database). | The test checks which lines were taken, not their order. |
| 10 | Docs | Four claims did not match the code: the daily check runs "after each file" (it runs once a day); logs pass through "a filter that removes secrets" (there is no such filter: headers and bodies are never logged, and a test proves it); logs are "structured, with a request id" (the id was never printed); API keys are "compared in constant time" (they are looked up by hash, so there is no comparison). | Each sentence now says what the code does. Log lines now carry the request id. |
| 11 | Docs | Idempotency keys are one space for the whole store, not one per API key. The docs did not say so. | Said in [the API design](../lld/api.md#idempotency): Mizan serves one store, so one key means one request. |
| 12 | Config | A fee default written on a nested record with `@DefaultValue` stopped the whole settings object from binding. Found by a test while building M5. | The defaults are in the record's constructor, and a test binds the settings with nothing set. |
| 13 | Tests | The limits count on real time, and a full test run sends hundreds of requests a second with one key. Once the suite grew, three tests that are not about limits got `429` and failed. | The test world has limits its tests do not reach. The limits keep their own test, on an instance with low ones. |

## Experiments

76 deliberate bugs. Ten got past the tests at first. Nine were caught after the changes below, and one could no longer be planted because its code was gone.

| Area | Bugs | Caught at first | After the fixes |
|---|---|---|---|
| Matching a line | 7 | 6 | 7 |
| Writing what matching decided: settled totals, entries, suspense, discrepancies | 14 | 11 | 14 |
| Import and the lines' table | 9 | 8 | 9 |
| Finance: retry and accept | 8 | 8 | 8 |
| The daily check, and its rule in the core | 8 | 6 | 7, and 1 deleted |
| Reports | 2 | 2 | 2 |
| Events | 10 | 8 | 10 |
| Limits, metrics, clean-up, the actuator | 14 | 13 | 14 |
| The schema | 4 | 4 | 4 |
| **All** | **76** | **66** | **75, and 1 deleted** |

The ten, and what each one taught:

| Bug | What it showed | Change |
|---|---|---|
| Matching reads the payment without its lock | Two lines of one payment, matched at the same moment, can lose one write. No test ran them at the same moment | A test runs a payment's capture line and refund line in two workers at once, 20 times |
| A matched line always closes the "missing" discrepancy | No test settled only part of what was missing | A test where a file has the refund but not the capture: the discrepancy stays open |
| A refund line's actual amount keeps its minus sign | No test had a refund line that nobody could explain | A test writes such a file by hand: `UNKNOWN_REFUND`, expected 0, actual 30.00 |
| A line held in suspense does not tell the store | No test looked at the event | The test of a wrong amount checks it |
| A duplicate is decided by "another line", not "an earlier line" | Both lines of a repeated transaction would be duplicates when both wait at once | A test uploads both files before matching: the earlier line counts, the later one is the duplicate |
| The daily check closes nothing | Its closing half was never needed | Deleted: finding 1 |
| The daily check reads the payment without its lock | A race no single-threaded test shows | A test holds the payment as a matching worker does, and checks that the daily check waits and opens nothing |
| The store's signature leaves out the timestamp | The test computed the expected signature with Mizan's own code, so both sides changed together | The test computes the HMAC itself |
| A redelivered event keeps its old count of tries | Then a `FAILED` event sent again would get no retries | The test checks the count starts from zero |
| The suspense metric has the wrong sign | The test compared it at a moment when suspense was zero | A test puts money in suspense and checks the metric goes up |

## Checked and kept

| Topic | Why it holds |
|---|---|
| A file is refused as a whole | Mizan posts nothing from a file it cannot trust. One bad line means the gateway's export is broken, not that one payment is wrong |
| Money that cannot be explained goes to suspense, not nowhere | The bank account in the ledger always equals the sum of the lines' net amounts. `Books` checks it after every test |
| A path that only starts like `/v1/payments/` | `/v1/payments/../ledger/balances` and four other spellings, sent byte for byte, do not get past the scope check. A test keeps it so |
| Requests without a valid key are not counted by the limits | Each costs one lookup by an indexed hash. A public deployment puts a load balancer with its own limits in front, as [running](../lld/running.md) says |
| Limits are per instance | Two instances allow twice the rate. Said in the docs; a shared counter is listed as next work |
| Webhook events are deleted after 90 days | A gateway that sends one again after that only causes one more sync, which is harmless (rule R2) |

## Result

M5 and M6 are done. Next: the proof (M7) and the final review, in [review 006](006-final-review.md).
