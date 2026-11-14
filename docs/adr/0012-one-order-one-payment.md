# ADR-0012: One order, one payment that counts

- **Status:** Accepted
- **Date:** 2026-11-14

## Context

Idempotency keys stop one request from making two payments. They do not stop two *different* payments for the same order: two browser tabs, a second card while the first attempt still waits for the bank's code, or a store that tries again while the first result is unknown. Each payment is correct alone, and the customer pays twice.

## Decision

The store's `reference` is the order, and **only one payment per order keeps money**:

1. A new payment is refused while another payment of the same order has an unknown result, or when the order is already paid.
2. When a payment holds or takes money, the order's other waiting payments are canceled.
3. If a canceled payment takes money later, it is a late capture: recorded, then refunded ([ADR-0009](0009-late-money-is-recorded-then-returned.md)).

Every transaction that changes a payment first takes a lock on the order, so two payments of one order are never judged at the same moment.

## Consequences

- **Good:** "the customer paid twice for one order" ends with one payment kept and one returned, without the store writing any code.
- **Good:** the rule reuses late capture. No new machinery.
- **Cost:** a store cannot pay one order with two payments on purpose. That is a non-goal.
- **Cost:** one more lock per transaction. It is held only for the length of a short transaction.

## Alternatives

- **Leave it to the store.** Every store then needs the same careful code, and most get it wrong.
- **Refuse every second payment while one is waiting.** A customer who wants to change cards is stuck for half an hour.
