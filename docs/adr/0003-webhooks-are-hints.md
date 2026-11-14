# ADR-0003: A webhook is a hint, not a fact

- **Status:** Accepted
- **Date:** 2026-11-14

## Context

A gateway sends a webhook when a payment changes. Webhooks arrive more than once, late, in the wrong order, or not at all: the card gateway that Mizan follows gives up after six tries. Its webhooks are protected by a shared secret in the body, not by a signature, so anyone who learns the secret can write one.

## Decision

A webhook only tells Mizan **which payment to look at**. The handler checks the secret, stores the event id, marks the payment for a sync, and answers `200`. The sync worker then fetches the record from the gateway.

Mizan does not read the status or the amounts from a webhook.

## Consequences

- **Good:** a forged or replayed webhook cannot change money. The worst it can do is cause one more fetch.
- **Good:** order and repeats do not matter.
- **Good:** the handler is fast and never fails because of the gateway.
- **Cost:** a change takes one fetch longer to appear.
- **Cost:** webhooks alone are not enough, so timers and the customer's return also trigger a sync. Mizan needs them anyway, for lost webhooks.

## Alternatives

- **Trust the webhook body.** Faster, and wrong on every point above.
