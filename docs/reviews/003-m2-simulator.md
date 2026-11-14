# Review 003 — M2: the simulator

- **Date:** 2026-11-14
- **Scope:** all of `mizan-simulator`: the card gateway, the pay-later provider, webhooks, faults, settlement files, the control API.
- **Method:** the [review checklist](../../CONTRIBUTING.md#review-checklist), read as a critic; then 50 deliberate bugs, each planted alone, with the tests run against it.

## Built

| Part | What it is |
|---|---|
| The card gateway | Tokens, create with the caller's id (the same id again charges nothing), the bank's page, fetch, capture, void, refund |
| The pay-later provider | Session, the provider's page, fetch, capture, close, refund with a key |
| Its own book | Per payment: what was held, taken and given back, and how many times it was charged. The proof run will compare Mizan with it |
| Webhooks | The card gateway's secret in the body; the provider's signature in a header; retried after 1 min, 10 min, 30 min, 1 h, 2 h, then dropped, as the real gateway's documents say |
| Faults | 7 faults of one call, 8 faults that stay on an order, 9 settlement file faults |
| A clock that tests move | Nothing waits in real time, except the faults that are about a caller's timeout |

It is a plain Java program on the JDK's small web server: no framework. 73 tests.

## Findings

| # | Area | Finding | Fix |
|---|---|---|---|
| 1 | Speed | An experiment removed "send the webhook before the answer", and the test still passed. The cause was not the test: **every call to the simulator took 44 ms**. The JDK's server writes an answer's headers and body apart, and the second part waited for the operating system (Nagle's algorithm). The webhook, sent 20 ms later by the clock thread, always won the race. | The server is started with "no delay". A call now takes about 3 ms. The proof run sends tens of thousands of calls, so this is the difference between seconds and minutes. The test now also checks that the webhook arrived before the answer. |
| 2 | Simplicity | A refund was refused by two checks: "the payment is paid and not voided", then "the amount is at most what is left". Two experiments showed the first check does nothing: an unpaid payment has nothing captured, and a payment voided after capture has nothing left. | One check. |
| 3 | Tests | A planned file fault of one order (for example a wrong fee) is used once. No test said so; only file faults without an order were tested. | A test. |
| 4 | Tests | The bank's page can refuse. Only "approve" was tested through the page; "decline" only through the simulator's own method. | A test through the page. |
| 5 | Correctness | A customer's return address used the card gateway's words for a pay-later payment, and would have failed for an expired session. | Each gateway's own words. Found while writing the tests, before the experiments. |
| 6 | Correctness | The file faults "wrong amount" and "short amount" kept the fee of the true amount. One planted fault then showed up as two differences (amount and fee). | The fee is computed from the line's amount, so one fault gives one difference. |
| 7 | Docs | The design said a fault is asked for in a payment's metadata. In the code a fault is planned for an **order**, through the control API. Three "faults" of the design are not faults at all: a late customer, a customer who leaves, two tabs. | [Gateways](../lld/gateways.md#faults) now has three tables: faults of one call, faults that stay, and things that happen outside Mizan. The control API is listed call by call. |

## Experiments

50 deliberate bugs. After the fixes above: 48 caught. The other two can no longer be planted: their code was deleted (finding 2).

| Area | Bugs | Caught at first | Caught after the fixes |
|---|---|---|---|
| Card gateway: create, tokens, test cards, capture, void, refund, its words | 16 | 14 | 14, and 2 deleted |
| Pay later: capture, refund key, statuses, amounts | 4 | 4 | 4 |
| The book | 2 | 2 | 2 |
| Webhooks: faults, retries, signature, due time | 10 | 9 | 10 |
| Faults of one call, fetch down, keys, another amount | 11 | 11 | 11 |
| Settlement files and their faults | 6 | 5 | 6 |
| The customer's page | 1 | 0 | 1 |
| **All** | **50** | **45** | **48, and 2 deleted** |

## Checked and kept

| Topic | Why it holds |
|---|---|
| The simulator is strict where the gateways' documents are silent | A capture above the amount, a refund above what is left, a second void, a used token: all refused, all tested |
| The same create twice | The second answer is the first payment. `charges` in the book stays 1 |
| A webhook sent early is sent while the simulator is locked | Safe: Mizan's webhook handler never calls the gateway ([workers](../lld/workers.md#a-webhook-comes-in)). A handler that did would wait for itself |
| The settlement file is in Mizan's neutral format, not in a real gateway's | Said plainly in [reconciliation](../lld/reconciliation.md#the-settlement-file) and in assumption A5 |
| The control API has no authentication | The simulator is a test tool. The docs say it must never be on a public address |

## What the simulator is not

It is not the gateway. It follows the public documents of a real card gateway and a real pay-later provider, and it is strict where they are silent. Until a run against a real test mode (layer 4 of the proof), assumptions A1 to A6 of the [design](../DESIGN.md#15-assumptions-to-confirm) stay open.

## Result

M2 is done. Next: payments (M3).
