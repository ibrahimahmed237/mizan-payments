# ADR-0007: Money is a whole number of halalas

- **Status:** Accepted
- **Date:** 2026-11-14

## Context

Money in floating-point numbers loses halalas. Money in decimals invites rounding in many places.

## Decision

An amount is a **64-bit whole number of halalas** with a currency code. `mizan-core` has a `Money` type that cannot be made from a fraction. Only `SAR` is accepted.

Rounding happens in one place only: the fee formula, which rounds half up and says so.

## Consequences

- **Good:** adding, comparing and storing are exact.
- **Good:** the API, the database, the gateways and the settlement file all use the same unit, so nothing is converted.
- **Cost:** people read `15000`, not `150.00`. The docs show both where it helps.

## Alternatives

- **`BigDecimal`.** Exact, and every division needs a rounding rule.
- **Floating point.** Not exact.
