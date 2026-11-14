# ADR-0001: One service and one database

- **Status:** Accepted
- **Date:** 2026-11-14

## Context

A payments backend could be several services with a message broker between them. The author's BNPL project, Qist, is built that way. Mizan's work is one flow around one record, the payment, and it must keep that record and the ledger in step.

## Decision

Mizan is **one service with one PostgreSQL database**. The code is split into modules by a build rule, not by a network:

- `mizan-core` holds the rules and has no framework.
- `mizan-app` holds the API, the workers and the adapters.

Background work runs inside the service. Rows waiting for work are picked with `FOR UPDATE SKIP LOCKED`.

## Consequences

- **Good:** a payment and its ledger entry are written in one transaction. No saga is needed.
- **Good:** one thing to run, to test and to explain.
- **Good:** two instances can run side by side; `SKIP LOCKED` keeps them from taking the same row.
- **Cost:** everything scales together. At 50 payments a second this does not matter.
- **Cost:** a slow part can affect the others. Gateway calls have strict timeouts, and no transaction is open during a call.

## Alternatives

- **Services and a broker.** More moving parts, and the hard problem (the payment and the ledger must agree) becomes harder.
- **A separate queue product for the workers.** A second system to keep in step with the database.
