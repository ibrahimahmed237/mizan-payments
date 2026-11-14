# ADR-0006: Plain SQL, no ORM

- **Status:** Accepted
- **Date:** 2026-11-14

## Context

The service needs row locks, `SKIP LOCKED`, unique partial indexes and conditional updates. These are the tools that make money safe.

## Decision

Repositories use Spring's `JdbcClient` with SQL written by hand. Rows map to Java records. The schema is in Flyway migrations.

## Consequences

- **Good:** every lock and every condition is visible in the query that takes it.
- **Good:** no hidden queries, no lazy loading, no cache between the code and the truth.
- **Cost:** more lines for simple reads and writes.
- **Cost:** a renamed column is found by tests, not by the compiler. Every query has a test against a real PostgreSQL.

## Alternatives

- **JPA.** Less code for simple cases; the locks and conditional writes this service lives on are harder to see and to get right.
