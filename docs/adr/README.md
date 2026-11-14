# Architecture decisions

An ADR (architecture decision record) explains one important choice: the problem, the decision and what it costs. ADRs are short and are never deleted. If a decision changes, a new ADR replaces the old one.

| # | Decision | Status |
|---|---|---|
| [0001](0001-one-service-one-database.md) | One service and one database | Accepted |
| [0002](0002-the-gateway-is-the-truth.md) | The gateway is the truth; one sync function copies it | Accepted |
| [0003](0003-webhooks-are-hints.md) | A webhook is a hint, not a fact | Accepted |
| [0004](0004-mizan-chooses-the-payment-id.md) | Mizan chooses the gateway's payment id | Accepted |
| [0005](0005-one-operation-in-flight.md) | One operation in flight, written down before the call | Accepted |
| [0006](0006-plain-sql.md) | Plain SQL, no ORM | Accepted |
| [0007](0007-money-in-halalas.md) | Money is a whole number of halalas | Accepted |
| [0008](0008-double-entry-ledger-with-suspense.md) | A double-entry ledger with a suspense account | Accepted |
| [0009](0009-late-money-is-recorded-then-returned.md) | Late money is recorded, then returned | Accepted |
| [0010](0010-a-simulator-with-its-own-book.md) | A simulator with its own book, and a proof run | Accepted |
| [0011](0011-no-card-data.md) | No card data in Mizan | Accepted |
| [0012](0012-one-order-one-payment.md) | One order, one payment that counts | Accepted |

## Template

```markdown
# ADR-NNNN: Title

- **Status:** Proposed | Accepted | Replaced by ADR-NNNN
- **Date:** YYYY-MM-DD

## Context
## Decision
## Consequences
## Alternatives
```
