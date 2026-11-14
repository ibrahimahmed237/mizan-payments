# Review 001 — Design, second review

- **Date:** 2026-11-14
- **Scope:** the design after review 000.
- **Method:** two passes. First as a **judge**: for every decision, argue the other side and ask "could this be simpler?". Then walk the flows again, because fixes make new gaps.

## The decisions, judged

| Decision | The case against | Verdict |
|---|---|---|
| One `sync` function for every path | A single function with every rule is hard to read | **Keep.** It replaces four handlers that would each need their own rules for repeats and order. It is pure, so each cell of its table is one small test |
| The gateway is the truth | Mizan becomes wrong when the gateway is wrong | **Keep.** The gateway moves the money; Mizan cannot overrule it. A record with another amount is refused |
| Webhooks are only hints | One more gateway call per change | **Keep.** The card gateway's webhooks have a shared secret in the body and stop after six tries. Trusting their content would be the weakest point of the system |
| An operation row before each call | More rows and a status to explain | **Keep.** Without it, a crash leaves no trace of a call that may have moved money |
| One table for operations, no refund table | A refund is a business thing and deserves a table | **Keep one table.** A refund has the same life as a capture: sent, unknown, done or failed. Two tables would hold the same columns |
| One order, one payment | Mizan decides something that belongs to the store | **Keep.** Every store needs it, the rule is small, and it reuses late capture. A store that wants split payments is a non-goal |
| Suspense for unmatched lines | Simpler: refuse to post them | **Keep.** The bank did get the money. Books that leave it out are wrong |
| Workers inside the service | A queue product is the usual answer | **Keep.** The work is rows in the same database as the facts. A queue would be a second system to keep in step with it |
| Pay later as a second gateway | Half the work again for one more method | **Keep, but smaller.** It proves the gateway interface is real: another id rule, no automatic capture, a refund key. Captures there are full only |
| Apple Pay | One more method | **Keep.** For Mizan it is a token of another kind. It costs one branch |
| Several businesses in one Mizan | Looks more complete | **Dropped** (in the first draft of the idea). Mizan is the payments service of one store. The author's other projects already show several tenants |
| Storing the gateway's answer for an idempotency key | The usual design | **Dropped** in review 000. A pointer to the payment is smaller and survives a crash |
| Sending a refund again by itself | Fewer failed refunds | **Dropped** in review 000 for gateways without a refund key. A failed report is cheap; a double refund is not |

## What this project adds to the author's earlier work

The author's BNPL project, Qist, already has a ledger, an outbox and a simple payment-provider simulator. A critic would ask what Mizan adds. The answer, checked against Qist's design: the gateway as the truth with one sync; results that stay unknown and are resolved later; webhooks coming *in*; an order paid in two tabs; settlement files, matching and suspense; and a proof run against a simulator that keeps its own book. None of these are in Qist. The ledger and the outbox are reused ideas, kept small here.

## Findings of the second walk

| # | Finding | Risk | Fix |
|---|---|---|---|
| 1 | The policy "keep late captures" would keep a late capture even when another payment of the same order already has the money. | The customer pays twice, by configuration | A late capture is always returned when another payment of the order holds or has money. `decide` gets this fact from the caller. |
| 2 | A create that ends as "no effect" set the payment to `FAILED`. The payment may have been canceled meanwhile (its order was paid by another payment). | A canceled payment turns into a failed one, and the store gets two different final events | "No effect" changes the payment only if it is still `PENDING`. |
| 3 | The ledger said a payment's receivable is never below zero. It is, for a while: a capture is paid out in full, and the refund is taken out of a later payout. | An invariant the tests would have to break | The invariant is now: the receivable **ends** at zero. |
| 4 | A pay-later webhook could not be tied to its payment before the provider's id was saved. | A false "unknown payment" discrepancy | The session carries Mizan's payment id as the provider's order reference. The webhook is matched by either id. |
| 5 | `expirePending` and `expireHold` could run while an operation was in flight. | A payment expires while its capture is on the way | Timers do not run while an operation is in flight. |

## Checked and kept

| Topic | Why it holds |
|---|---|
| Records in any order | Property P4 of [sync](../lld/sync.md#what-is-always-true): totals keep the higher value, statuses only step forward |
| A canceled payment whose create was still in flight, and that then turns out paid | It is a late capture: recorded and returned |
| Two syncs of two payments of one order at the same moment | Both take the order's lock first, so one sees the other's result |
| A refund line in a file before its capture line | The receivable goes below zero and comes back. The entries are independent |
| The same key after 30 days | The key is gone, but the order is already paid: `409 reference_already_paid` |
| Simple words | Each page was read once more for long sentences and for words that are not in the glossary |

## Result

All findings are fixed in the documents. The docs check passes. The design is ready for M0.
