# Sync

*Sync* copies the gateway's record of one payment into Mizan. It is the only code that changes a payment's money. A webhook, the customer's return, a timer, the end of an operation, and reconciliation all call it (rule R1 of the [design](../DESIGN.md#5-the-seven-rules)).

It is a pure function in `mizan-core`: no database, no network, no clock of its own.

```
decide(payment, operation in flight or none, snapshot, context, now) → decision
```

The caller fetches the snapshot, locks the payment, calls `decide`, and saves the decision in one transaction.

The **context** is the little that `decide` cannot see in the payment: the late-capture policy, the hold window, and whether another payment of the same order already holds or has money.

## The snapshot

What the gateway says about a payment, in one shape for every gateway. The adapter builds it ([gateways](gateways.md#normalizing)).

| Field | Meaning |
|---|---|
| `status` | `NOT_FOUND`, `INITIATED`, `AUTHORIZED`, `PAID`, `FAILED`, `VOIDED` or `EXPIRED` |
| `amount`, `currency` | The payment's amount at the gateway |
| `captured` | Total taken so far, in halalas |
| `refunded` | Total given back so far |
| `actionUrl` | Where the customer must go, if anything is left to do |
| `failureCode`, `failureMessage` | Why it failed |
| `scheme`, `last4` | The card, for display |

The adapter promises: `0 ≤ refunded ≤ captured ≤ amount`, and `captured > 0` exactly when the status is `PAID`. A payment voided *after* capture is reported as `PAID` with `refunded = captured`: for the books, that is a full refund.

## Old snapshots are normal

Two fetches of one payment can finish in any order. So Mizan may see a newer record first and an older one after it. An older record is not an error. It is ignored.

Mizan knows a record is old in two ways:

| Sign | Rule |
|---|---|
| A total is lower than Mizan's | Totals only grow. Mizan keeps the higher one |
| The status is behind the last one Mizan saw | The payment remembers the gateway's last status (`gatewayStatus`). A status with a lower **step** is ignored |

| Step | Gateway status |
|---|---|
| 0 | `NOT_FOUND` |
| 1 | `INITIATED` |
| 2 | `AUTHORIZED` |
| 3 | `FAILED`, `VOIDED`, `EXPIRED` (ends without money) |
| 4 | `PAID` |

A status with the same step as the last one (for example `FAILED` after `VOIDED`) is also ignored: the first end wins. `PAID` is never ignored when it brings a higher total.

## The decision

A decision is the payment's new values and a list of **effects**. The caller turns each effect into rows.

| Effect | Becomes |
|---|---|
| `Captured(amount)` | A ledger entry: capture |
| `Refunded(amount)` | A ledger entry: refund |
| `OperationFinished(result)` | The operation in flight is closed |
| `ExternalRefund(amount)` | A refund record that Mizan did not start (made in the gateway's dashboard) |
| `CancelSiblings` | Other unfinished payments of the same order are canceled ([one order, one payment](payments.md#one-order-one-payment-that-counts)) |
| `FollowUp(kind)` | An operation Mizan starts by itself: `AUTO_CAPTURE`, `AUTO_VOID`, `AUTO_REFUND` |
| `Notify(event)` | An event for the store, in the outbox |
| `Anomaly(type)` | A discrepancy for a person to look at |

## The steps, in order

### Step 1 — refuse a record that cannot be this payment

| Check | Result |
|---|---|
| `snapshot.amount ≠ payment.amount`, or the currency differs | Change nothing. `Anomaly(AMOUNT_CONFLICT)` |

This stops a customer who changes the amount on the way: the gateway may say "paid", but not for this payment's amount. It is checked for every status except `NOT_FOUND`.

### Step 2 — money taken

`delta = snapshot.captured − payment.captured`. If `delta > 0`:

| Payment was | Result |
|---|---|
| `PENDING` or `AUTHORIZED` | `CAPTURED`. Effects: `Captured(delta)`, `Notify(payment.captured)`, `CancelSiblings` |
| `CAPTURED` (a capture Mizan did not start) | Stays `CAPTURED`. Effects: `Captured(delta)`, `Notify(payment.captured)` |
| `FAILED`, `CANCELED` or `EXPIRED` | **Late capture.** `CAPTURED` with `late = true`. Effects: `Captured(delta)`, `Notify(payment.captured)`, and `FollowUp(AUTO_REFUND)` when the policy is "refund" or another payment of the order already has the money. If the money is kept: `CancelSiblings` |

A `CREATE` or `CAPTURE` operation in flight is finished as succeeded.

### Step 3 — money given back

`delta = snapshot.refunded − payment.refunded`. If `delta > 0`: effect `Refunded(delta)`.

| Operation in flight | Result |
|---|---|
| A `REFUND` of amount `a`, and `delta ≥ a` | The operation succeeded: `Notify(refund.succeeded)`. If `delta > a`, the rest is an `ExternalRefund(delta − a)` |
| A `REFUND` of amount `a`, and `delta < a` | `ExternalRefund(delta)`. The operation stays in flight |
| None, or another kind | `ExternalRefund(delta)` |

Each `ExternalRefund` has its own `Notify(refund.succeeded)`.

### Step 4 — status, when no money was ever taken

Only when `payment.captured = 0` and the snapshot's status is not `PAID`. Old statuses were already dropped by the step rule above.

| Payment ↓ / Snapshot → | `INITIATED` | `AUTHORIZED` | `FAILED` | `VOIDED` | `EXPIRED` |
|---|---|---|---|---|---|
| `PENDING` | Keep. Save `actionUrl`, if the record has one | → `AUTHORIZED`, `CancelSiblings` | → `FAILED` | → `CANCELED` | → `EXPIRED` |
| `AUTHORIZED` | (old, ignored) | Keep | → `FAILED` (the gateway withdrew the hold) | → `CANCELED` | → `EXPIRED` |
| `FAILED`, `CANCELED`, `EXPIRED` | Keep | Keep. **Late hold:** `FollowUp(AUTO_VOID)` | Keep | Keep | Keep |

- Every change of state has a `Notify`.
- When a payment becomes `AUTHORIZED`, the hold's end is set: `now + hold window`. If the store asked for automatic capture and the gateway does not capture by itself (pay later), `FollowUp(AUTO_CAPTURE)` is added.
- A record without an address for the customer does not take a saved address away. The pay-later provider gives the address only in the answer to the create call.
- `NOT_FOUND` changes nothing here. It matters only for a `CREATE` in flight ([payments](payments.md#when-the-result-is-not-known)).

### Step 5 — close the operation in flight, if the snapshot settles it

| Operation | Snapshot | Result |
|---|---|---|
| `CREATE` | Anything but `NOT_FOUND` | Succeeded (the gateway has the payment) |
| `CANCEL` | `VOIDED` | Succeeded |
| `CANCEL` | `PAID` | Failed: `already_captured`. `Notify(cancel.failed)` |
| `CAPTURE` | `VOIDED` or `EXPIRED` | Failed: `hold_gone`. `Notify(capture.failed)` |

Anything else leaves the operation in flight for the [operation rules](payments.md#when-the-result-is-not-known).

### Step 6 — remember

`gatewayStatus` becomes the snapshot's status, if its step is higher. `lastSyncedAt = now`.

## Follow-ups happen once

A follow-up is saved on the payment and started when no operation is in flight (rule R4). A payment gets each kind at most once: a second late hold does not start a second void.

## Timers

Two more pure functions. Each runs right after a sync, so the decision is made on fresh facts. Neither runs while an operation is in flight.

| Function | When | Result |
|---|---|---|
| `expirePending` | `PENDING`, the waiting time is over, and the gateway's last status is `INITIATED` | → `EXPIRED`, `Notify(payment.expired)` |
| `expireHold` | `AUTHORIZED`, the hold's end has passed, and the gateway's last status is `AUTHORIZED` | → `EXPIRED`, `Notify(payment.expired)`, `FollowUp(AUTO_VOID)` |

`expireHold` exists because of assumption A4: the gateway may show `authorized` forever after the bank released the money.

## Anomalies

| Type | Meaning |
|---|---|
| `AMOUNT_CONFLICT` | The gateway's record has another amount or currency than the payment |

An anomaly changes nothing in the payment, not even `gatewayStatus`. It opens one discrepancy per payment and type.

## What is always true

These are tested with generated cases: any payment, any snapshots, any order.

| # | Property |
|---|---|
| P1 | **Safe to repeat.** Applying the same snapshot twice gives no effects the second time |
| P2 | **Money only moves forward.** `captured` and `refunded` never go down |
| P3 | **The ledger follows.** The sum of `Captured` effects equals the change in `captured`; the same for `Refunded` |
| P4 | **Order does not matter.** Take the records a payment really had at the gateway, one after another. Apply them in any order, with repeats. The payment ends with the same state, `captured`, `refunded` and `gatewayStatus` as when they are applied in the true order |
| P5 | **Limits hold.** `0 ≤ refunded ≤ captured ≤ amount`, always |
| P6 | **A wrong amount changes nothing.** A decision with `AMOUNT_CONFLICT` has no other effect |
| P7 | **No money, no capture.** A payment is `CAPTURED` exactly when `captured > 0` |

## Tests

| Test | Proves |
|---|---|
| A table test: every payment state × every snapshot status | Steps 2 to 5, cell by cell |
| Property tests P1 to P7 | The invariants, for thousands of generated cases |
| Old snapshot tests | A lower total and a status behind are ignored, without an anomaly |
| Late capture and late hold tests | The follow-ups, and that each happens once |
| Anomaly tests | Each anomaly; nothing else changes |
| Timer tests | Expiry only on fresh facts; a hold is not expired before its end; no expiry while an operation is in flight |
