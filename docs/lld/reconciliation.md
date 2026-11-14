# Reconciliation

Reconciliation answers one question: **did the gateway pay the store what Mizan says it should?**

## The settlement file

The gateway pays out once a day and gives a file for that payout. Mizan reads one neutral format. A real gateway's file is turned into it by a small converter, written when a real file is at hand (assumption A5). The simulator writes the neutral format directly.

```
H,payout_id,gateway,currency,payout_date,total_net,line_count
L,transaction_id,payment_id,type,gross,fee,net,occurred_at
L,...
```

| Field | Meaning |
|---|---|
| `payout_id` | The gateway's id of this payout. One file per payout |
| `total_net` | The amount sent to the bank |
| `transaction_id` | The gateway's id of this line. The same transaction is never paid twice |
| `payment_id` | The gateway's payment id |
| `type` | `capture`, `refund`, or anything else |
| `gross` | The amount of the capture; negative for a refund |
| `fee` | What the gateway kept; may be negative when it returns a fee |
| `net` | `gross − fee` |

Amounts are in halalas.

## Step 1 — accept or refuse the file

The file is read one line at a time. It is refused as a whole, and nothing is saved, if:

| Check | Error |
|---|---|
| The header or a line cannot be read; a number is not a whole number; a field is missing | `malformed_file` |
| The file is over 50 MB, or has more than 500,000 lines | `file_too_large` |
| The gateway is not known, or the currency is not `SAR` | `wrong_gateway`, `wrong_currency` |
| `line_count` is not the number of lines | `line_count_mismatch` |
| For a line, `net ≠ gross − fee` | `line_does_not_add_up` |
| A capture line with `gross ≤ 0`, a refund line with `gross ≥ 0`, or a fee larger than the amount | `malformed_file` |
| The sum of `net` is not `total_net` | `payout_total_mismatch` |
| The same `payout_id` was imported with other content | `409 payout_exists` |

The same file again (same `payout_id`, same hash) is answered with the first import: `200` and the header `Idempotent-Replay: true`. Nothing happens twice.

`POST /v1/settlements` takes the file itself as the body, as text, up to 50 MB. The answer is the file and how far its matching has come:

```json
201 Created

{ "id": "...", "gateway": "CARD_GATEWAY", "payout_id": "po_card_2026-11-14", "payout_date": "2026-11-14",
  "total_net": 28909, "line_count": 3,
  "lines": { "new": 3, "needs_sync": 0, "matched": 0, "suspense": 0, "accepted": 0 },
  "complete": false, "created_at": "..." }
```

The import only saves. Matching is the matching worker's job; `GET /v1/settlements/{id}` shows its progress, and `complete` turns true when no line waits.

A refused file is the gateway's mistake or a broken upload. Mizan posts nothing from a file it cannot trust as a whole.

## Step 2 — match each line

The matching worker takes lines in file order, with the same lease as the sync worker: a line it took is left alone for 30 seconds, and a worker that dies in the middle of a file is followed by another. Each line is matched in one transaction that locks the line, then the order and the payment.

```mermaid
flowchart TB
  L[Line] --> T{Known type?}
  T -->|no| S1[UNKNOWN_LINE_TYPE]
  T -->|yes| D{Transaction seen before?}
  D -->|yes| S2[DUPLICATE_SETTLEMENT]
  D -->|no| P{Payment found?}
  P -->|no| S3[UNKNOWN_PAYMENT]
  P -->|yes| M{Fits what is unsettled?}
  M -->|yes| OK[Post the settle entry]
  M -->|no, first try| SY[Sync the payment, then try again]
  SY --> M
  M -->|no, after the sync| S4[NOT_CAPTURED_HERE, AMOUNT_MISMATCH or UNKNOWN_REFUND]
```

| Line | Fits when | If it fits |
|---|---|---|
| Capture, gross `g` | `g ≤ capturedAmount − settledCaptured` | `settledCaptured += g`, entry `SETTLE_CAPTURE` |
| Refund, amount `r` | `r ≤ refundedAmount − settledRefunded` | `settledRefunded += r`, entry `SETTLE_REFUND` |

A line that fits but leaves part of the payment unsettled is fine: the rest is expected in a later file, and the daily check watches it.

**Seen before** is decided by the order the lines were written in, across files: a line is a duplicate when an earlier line has the same gateway and transaction id. So the answer is the same whatever order the workers take the lines in.

The sync before giving up is rule R1 at work. It finds a capture Mizan lost and a refund made in the gateway's dashboard. The sync is a gateway call, so it runs outside the transaction (rule R5): the line is marked `NEEDS_SYNC`, the payment is synced, and the line is tried once more. A sync that only looks starts no follow-up; a late capture found this way is returned by the sync worker a moment later.

| The line does not fit, after the sync | Discrepancy |
|---|---|
| Capture line, the payment has nothing captured | `NOT_CAPTURED_HERE` |
| Capture line, the payment has less unsettled than the line | `AMOUNT_MISMATCH` |
| Refund line, the payment has less unsettled refund than the line | `UNKNOWN_REFUND` |

A line with a discrepancy is still money that reached the bank. Its net amount is posted to `suspense` (rule R7) and a discrepancy is opened with that amount.

If the gateway cannot be reached for the sync, the line waits as `NEEDS_SYNC` and is tried again 30 seconds later. The file's progress shows it.

| Line status | Meaning |
|---|---|
| `NEW` | Not matched yet |
| `NEEDS_SYNC` | It did not fit; its payment is synced before the last try |
| `MATCHED` | Settled: the payment's settled totals and the ledger show it |
| `SUSPENSE` | It did not fit. Its net amount is in suspense and a discrepancy is open |
| `ACCEPTED` | It did not fit, and finance accepted the difference |

### Fees

For a matched capture line, the fee is compared with the fee table.

```
base = round_half_up(gross × percent) + fixed
fee  = base + round_half_up(base × VAT)
```

| Setting | Meaning |
|---|---|
| `percent` (in hundredths of a percent), `fixed` (halalas) | Per gateway, from the store's contract ([settings](running.md#configuration)). A table per card scheme is left for later: one rule per gateway is enough to find a wrong fee |
| `VAT` | 15% on the fee |

A refund line is expected to cost nothing. A gateway that keeps or returns a fee on refunds shows up as `FEE_MISMATCH` until the table says otherwise.

If the line's fee differs: the line is posted with the **real** fee, and a `FEE_MISMATCH` discrepancy records expected and actual. No money goes to suspense: the money is explained, only its price is not.

## Step 3 — the daily check

Once a day, and after each file:

| Finds | Opens |
|---|---|
| A payment with `capturedAmount − settledCaptured > 0`, captured more than N days ago (default 3) | `MISSING_FROM_SETTLEMENT`, with the amount |
| The same for refunds | `MISSING_FROM_SETTLEMENT` |

One open discrepancy of this type per payment, with the missing amount as `expected`. It closes by itself (`SETTLED_LATER`) when a later line settles the rest, or at the next check that finds nothing missing.

## Discrepancies

| Field | Meaning |
|---|---|
| `type` | See the tables above, plus the sync anomalies |
| `paymentId`, `lineId` | What it is about |
| `expected`, `actual` | In halalas, when they apply |
| `suspenseAmount` | The net amount held in suspense for it. Zero for fee mismatches, missing settlements and anomalies |
| `status` | `OPEN` or `RESOLVED` |
| `resolution` | `REMATCHED`, `SETTLED_LATER`, `ACCEPTED` |
| `note` | Why finance accepted it |

| Action | Who | Does |
|---|---|---|
| `POST /v1/discrepancies/{id}/retry` | Finance | Syncs the payment and matches the line again. If it fits now: entry `REMATCH`, resolution `REMATCHED`. If not: `409 still_unmatched`, and nothing changes. Only for `UNKNOWN_PAYMENT`, `NOT_CAPTURED_HERE`, `AMOUNT_MISMATCH` and `UNKNOWN_REFUND`; any other type is `409 not_retryable` |
| `POST /v1/discrepancies/{id}/accept` with `{"note": "..."}` | Finance | Entry `ACCEPT`: the money leaves `suspense` for `adjustments`. Resolution `ACCEPTED`; the line becomes `ACCEPTED`. A discrepancy that holds no money is closed with no entry. The note is needed: 1 to 500 characters (`422 note_required`) |

A discrepancy that is resolved already: `409 already_resolved`.

Two more types come from outside reconciliation and are kept in the same list, with no money: `AMOUNT_CONFLICT` (from [sync](sync.md#anomalies)) and `UNKNOWN_PAYMENT_EVENT` (from a [webhook](workers.md#a-webhook-comes-in)). One open discrepancy of a type per payment.

## Settlement faults

The simulator can break its own settlement file. The proof run plants these and checks that each one is reported, and nothing else.

| Fault | The file | Mizan must |
|---|---|---|
| `missing_line` | Leaves a capture out | Open `MISSING_FROM_SETTLEMENT` after N days |
| `extra_line` | Adds a line for a payment that does not exist | Open `UNKNOWN_PAYMENT`, money in suspense |
| `wrong_amount` | A capture line with more than was captured | Open `AMOUNT_MISMATCH`, money in suspense |
| `short_amount` | A capture line with less than was captured | Match the line; open `MISSING_FROM_SETTLEMENT` for the rest after N days |
| `wrong_fee` | A fee that is not the table's | Open `FEE_MISMATCH`, post the real fee |
| `duplicate_line` | The same transaction in two files | Open `DUPLICATE_SETTLEMENT`, money in suspense |
| `lost_capture` | A line for a capture that Mizan holds as failed (the answer and the webhook were lost, and the sync worker was off) | Sync, record the late capture, match the line. No discrepancy |
| `dashboard_refund` | A refund line for a refund made outside Mizan | Sync, record the external refund, match the line. No discrepancy |
| `chargeback_line` | A line of type `chargeback` | Open `UNKNOWN_LINE_TYPE`, money in suspense |
| `bad_total` | `total_net` is wrong | Refuse the file |
| `broken_file` | A cut-off file | Refuse the file |
| `same_file_twice` | The same file again | Answer as the first time; post nothing |

## Tests

| Test | Proves |
|---|---|
| Parser tests | Each refusal of step 1; a large file is read as a stream |
| Matching tests (pure, in `mizan-core`) | Each branch of the flow, each discrepancy type |
| Property test: any payments and any file made from them, lines in any order | After matching: `bank` = the sum of `net`; I1 to I4 of the [ledger](ledger.md#what-must-always-be-true) hold |
| Import and matching tests with the database (`ReconciliationIT`, `MatchingIT`) | The same file twice; another file with the same payout; a crash in the middle of matching continues; two workers settle each line once |
| Fault tests | Each row of the settlement faults table, with the simulator's real files |
| Discrepancy tests | Retry, accept, closing by itself; who may do what |
| The books after every test | I1 to I4, and the bank holds exactly the net of the handled lines |
