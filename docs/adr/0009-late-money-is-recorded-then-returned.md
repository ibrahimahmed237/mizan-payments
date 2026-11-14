# ADR-0009: Late money is recorded, then returned

- **Status:** Accepted
- **Date:** 2026-11-14

## Context

Mizan told the store that a payment expired. An hour later the customer finishes the bank's page and the gateway takes the money. The store never delivered the order.

## Decision

Mizan **records what happened, then returns the money**:

1. The payment becomes `CAPTURED`, marked `late`, with its ledger entry. The truth is never ignored.
2. The store is told.
3. Mizan starts a full refund by itself.

A hold that appears late is voided. The policy for late captures can be set to `KEEP` for stores that would rather deliver.

## Consequences

- **Good:** the customer is not left paying for nothing.
- **Good:** the books show the capture and the refund, as the gateway's settlement file will.
- **Cost:** `FAILED`, `CANCELED` and `EXPIRED` are final for the store but not for the truth. The docs say so plainly.
- **Cost:** a customer who paid late and wanted the order gets a refund. `KEEP` exists for that.

## Alternatives

- **Ignore late money.** The customer pays and gets nothing, and the books are wrong.
- **Never expire.** The store cannot release stock.
