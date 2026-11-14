# ADR-0004: Mizan chooses the gateway's payment id

- **Status:** Accepted
- **Date:** 2026-11-14

## Context

Mizan sends "create payment" and the answer never comes. The card may have been charged. If the gateway chose the id and the answer is lost, Mizan has no way to ask about that payment, and sending the request again could charge twice.

## Decision

For the card gateway, **Mizan makes the payment's id before the first call** and saves it. The gateway creates a payment with a given id at most once.

- Answer lost: fetch the payment by that id.
- Still "not found" one quiet minute after the call: the request never arrived. The payment is marked failed.

Mizan does not send a create again. It could not: the card's token is used once and not stored ([ADR-0011](0011-no-card-data.md)). If the gateway processes the request after all, the money is found by the next sync and returned ([ADR-0009](0009-late-money-is-recorded-then-returned.md)).

The pay-later provider chooses its own ids. That is safe there for another reason: until the customer is sent to the provider's page, nothing can be paid, and the page address is in the lost answer.

## Consequences

- **Good:** a lost answer always has a way back to the truth.
- **Good:** the id is also the link back from a webhook or a settlement line to the payment.
- **Cost:** depends on the gateway accepting the caller's id (assumption A1 of the design). A gateway without it needs another lookup, such as a search by the order reference.

## Alternatives

- **An idempotency key header.** Protects a repeated request. Mizan does not repeat, and still needs a way to look the payment up.
- **Mark the payment failed on a timeout.** The customer may have paid.
