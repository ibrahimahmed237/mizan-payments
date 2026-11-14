# ADR-0011: No card data in Mizan

- **Status:** Accepted
- **Date:** 2026-11-14

## Context

A system that stores or passes card numbers must meet the card industry's security standard (PCI DSS) in full.

## Decision

The customer types the card on the **gateway's page**, which returns a one-time **token**. Mizan receives only the token, uses it in one call, and forgets it. It keeps the card scheme and the last four digits for display.

## Consequences

- **Good:** the hardest security duty stays with the gateway.
- **Good:** a leak of Mizan's database contains no card numbers and no usable tokens.
- **Cost:** the store's checkout must use the gateway's page or its script.

## Alternatives

- **Take the card number in the API.** Simple to call, and a large security duty.
