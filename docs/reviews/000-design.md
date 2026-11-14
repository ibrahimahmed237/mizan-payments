# Review 000 — Design

- **Date:** 2026-11-14
- **Scope:** the high-level design, the decisions and the low-level designs, before any code.
- **Method:** walk every flow end to end and ask at each step: what if the process dies here; what if the answer is lost; what if this happens twice, late, or before something else; what if two of these run at once; who else can call this.

## Findings

| # | Finding | Risk | Fix |
|---|---|---|---|
| 1 | The worker was to send a create again when the gateway said "not found". It cannot: the card's token is used once and never stored. The two parts of the design did not fit. | An impossible step in the most important flow | Mizan never sends a create again. After one quiet minute, "not found" means the request never arrived and the payment fails. If the gateway takes the money after all, late capture returns it. |
| 2 | The idempotency record stored the first answer. A crash between "payment made" and "answer saved" left a key with no answer, and the same key was then refused forever. | A store that retries after a crash is stuck | A key now points to the payment or operation it made, written in the same transaction. A repeat returns that thing as it is now. Nothing else is stored. |
| 3 | `attempts` was written after the call. A crash in between hides a call that may have reached the gateway. | The worker could decide on wrong facts | `attempts` and `lastAttemptAt` are committed before each call. |
| 4 | A record with a lower total, or a status behind, raised an anomaly. But two fetches of one payment can finish in any order, so an older record after a newer one is normal. | False alarms for finance on every busy day | Totals keep the higher value. The payment remembers the gateway's last status, and a status behind it is ignored. Three anomaly types are gone. |
| 5 | A refund with an unknown result was sent again after a minute. The card gateway has no refund key, so a first call that was only slow would refund twice. | Money given back twice | A refund is repeated only at a gateway that takes a refund key. Otherwise it is reported as failed, and the store decides. |
| 6 | Capture, cancel and refund checked their rules on totals that could be old (a refund made in the gateway's dashboard). | A refund larger than what is left | Each of the three starts with a fresh sync. |
| 7 | Nothing stopped two payments of one order from both taking money: two tabs, or a second try while the first result is unknown. | The customer pays twice, although each payment is correct | One order, one payment that counts ([ADR-0012](../adr/0012-one-order-one-payment.md)). The loser becomes a late capture and is returned. |
| 8 | The customer's return page needs no key and started a gateway fetch every time. | A stranger floods the gateway through Mizan | At most one sync of a payment every two seconds from this page, and a request limit per address. |
| 9 | A settlement line that did not match posted its fee to `gateway_fees`. A fee of an unexplained line is not explained either. | Wrong expense in the books | An unmatched line posts only what reached the bank, to `suspense`. The fee is posted when the line is explained. |
| 10 | No event told the store that a capture or a cancel failed after an unknown result. | The store waits forever | `capture.failed` and `cancel.failed`. |
| 11 | A settlement line of another type (a chargeback) had no rule. A capture line with a negative amount was not refused. A line with less than was captured had no stated result. | Gaps in matching | `UNKNOWN_LINE_TYPE`; sign checks when the file is read; a short line matches and the rest is watched by the daily check. |
| 12 | "Operations" meant both the finance people and the rows that track gateway calls. | Confusing docs | The people are "Finance", their scope is `backoffice`, and the page about running the service is `running.md`. |
| 13 | The design promised that Mizan adds under 50 ms to a gateway call. With a fresh sync before each command that is not true. | A claim the code cannot keep | Replaced with what is true: one gateway call and two short transactions to create a payment, and no lock held during a call. |
| 14 | A held payment that the gateway reports as failed was an anomaly, and the payment stayed held. | A stuck payment | `AUTHORIZED` → `FAILED`: the gateway withdrew the hold. |
| 15 | One sequence diagram did not render: a semicolon in a message. | A broken picture | Fixed. Every diagram is rendered by the docs check. |

## Checked and kept

| Topic | Why it holds |
|---|---|
| The process dies between "operation saved" and the call | The operation is in flight with `attempts` written. The worker fetches after the quiet minute |
| The process dies between the call and "result saved" | The same: the fetch shows what the gateway did |
| A webhook before the create call's answer | The payment row is committed before the call |
| A webhook twice, late, out of order, forged | It only marks the payment for a sync |
| Two workers | `SKIP LOCKED`, a lease on `nextSyncAt`, and sync is safe to repeat |
| A ledger entry twice | The unique key built from the total after the fact |
| A settlement file twice | The payout id and the file's hash |
| A settlement line for a payment Mizan lost | The sync before giving up records the late capture, then the line matches |
| A changed amount at the gateway | `AMOUNT_CONFLICT`: never accepted as paid |
| A changed `status=paid` in the customer's return address | Query parameters are not read |

## Result

All findings are fixed in the documents. Every link works and every diagram renders (`scripts/check-docs.py`, and each diagram drawn with the Mermaid tool).
