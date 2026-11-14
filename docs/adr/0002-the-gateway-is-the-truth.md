# ADR-0002: The gateway is the truth; one sync function copies it

- **Status:** Accepted
- **Date:** 2026-11-14

## Context

Mizan learns about a payment in many ways: the answer to its own call, a webhook, the customer's return, a timer, a settlement file. If each way updates the payment with its own code, they can disagree, and each one needs its own handling of repeats and order.

## Decision

**The gateway's record of a payment is the truth. Mizan's payment is a copy.** One pure function, `sync`, takes the gateway's record (a snapshot) and decides what changes: state, totals, ledger entries, events. Every way of learning ends by fetching the record and calling `sync`.

`sync` works on totals ("captured so far", "refunded so far"), not on events. A total can only grow. So applying a snapshot twice, or an old one after a new one, changes nothing.

## Consequences

- **Good:** one function carries all the rules about money. It is pure, so every case is a unit test and the invariants are property tests.
- **Good:** repeats and order stop being problems, without sequence numbers or event sorting.
- **Good:** when Mizan and the gateway disagree, there is a rule: follow the gateway, and report records that cannot be true.
- **Cost:** one extra call to the gateway (the fetch) on most paths.
- **Cost:** Mizan trusts the gateway. A wrong record at the gateway becomes a wrong record in Mizan, unless it moves backwards or changes the amount, which `sync` refuses.

## Alternatives

- **Apply each webhook as an event.** Needs order and exactly-once handling, and trusts the webhook's content.
- **Keep Mizan's own state as the truth and correct the gateway.** The gateway moves the money; Mizan cannot overrule it.
