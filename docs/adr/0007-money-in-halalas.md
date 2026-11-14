# ADR-0007: Money is a whole number of halalas

- **Status:** Accepted
- **Date:** 2026-11-14

## Context

Money in floating-point numbers loses halalas. Money in decimals invites rounding in many places.

## Decision

An amount is a **64-bit whole number of halalas** (a Java `long`) with a currency code. Only `SAR` is accepted. The few places where money meets text, such as a provider that writes `"150.00"`, go through one class, `Money`, which reads such text exactly or refuses it.

Rounding happens in one place only: the fee formula, which rounds half up and says so.

## Consequences

- **Good:** adding, comparing and storing are exact.
- **Good:** the API, the database, the gateways and the settlement file all use the same unit, so nothing is converted.
- **Cost:** people read `15000`, not `150.00`. The docs show both where it helps.

## Alternatives

- **`BigDecimal`.** Exact, and every division needs a rounding rule.
- **Floating point.** Not exact.
