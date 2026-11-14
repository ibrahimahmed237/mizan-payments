# ADR-0008: A double-entry ledger with a suspense account

- **Status:** Accepted
- **Date:** 2026-11-14

## Context

Mizan must show that the money the gateway paid out is the money that was sold. A status on a payment cannot show that. And a settlement file sometimes has money Mizan cannot match to anything.

## Decision

Every money fact is an entry in a **double-entry ledger**: debits equal credits. Entries are never changed.

A settlement line that cannot be matched is still posted, because the bank did receive the money. Its other side goes to a **suspense** account, and a discrepancy is opened. When the line is explained, a new entry moves the money out of suspense.

The database refuses an unbalanced entry and any change to an old one.

## Consequences

- **Good:** questions about money become sums over accounts. "What does the gateway still owe us?" is the balance of one account.
- **Good:** unexplained money is a number that must be zero, visible to everyone.
- **Good:** an auditor can follow any riyal.
- **Cost:** more rows and more rules than a status column.
- **Cost:** people must learn six accounts. The docs show each fact as a small table.

## Alternatives

- **Refuse to post lines that do not match.** Then the bank account in the books is not the bank account.
- **Guess the match.** A wrong guess hides a real problem.
