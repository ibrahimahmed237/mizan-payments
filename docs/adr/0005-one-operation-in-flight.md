# ADR-0005: One operation in flight, written down before the call

- **Status:** Accepted
- **Date:** 2026-11-14

## Context

A capture, a cancel and a refund are calls to the gateway that can be slow or lose their answer. The card gateway reports totals, and it has no idempotency key for refunds. If two refunds run at the same time and one answer is lost, a change in "refunded so far" cannot be tied to one of them.

## Decision

Each call to the gateway is an **operation** row, written and committed **before** the call:

1. Fetch the gateway's record. Transaction: lock the payment, sync, check the rule, insert the operation `IN_FLIGHT`.
2. Call the gateway. No transaction is open.
3. Transaction: sync, and close the operation if the totals moved.

**A payment has at most one operation in flight.** A unique index enforces it. A second request gets `409`.

An operation with no result waits one quiet minute, then Mizan fetches. If nothing changed, it sends the call again once, only when a repeat cannot move money twice: a capture or a void, or a refund at a gateway that takes a refund key. Otherwise the operation is reported as failed.

## Consequences

- **Good:** a crash at any point leaves a row that says what was being done. A worker finishes it.
- **Good:** a change in a total has one cause, so "did my refund work?" has an answer.
- **Good:** no database lock is held during a slow call.
- **Cost:** the store must wait or retry when a payment is busy.
- **Cost:** a refund whose answer was lost at the card gateway is reported as failed even if it worked a moment later. The next sync corrects the totals, and the store sees the refund in the payment.

## Alternatives

- **Let operations run in parallel.** Faster, and unsafe with totals.
- **Hold the payment's lock during the call.** Simple, and one slow gateway call blocks everything on that payment and holds a connection.
