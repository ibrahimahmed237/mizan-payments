# Mizan in one page

For an interview, or for anyone who wants the project in ten minutes.

## The business

An online store in Saudi Arabia takes money by card (mada, Visa, Mastercard), by Apple Pay, and by pay later. A **gateway** moves the money. Every few days the gateway pays the store into its bank account, less its fees, and sends a **settlement file** that lists each payment in the payout.

Mizan is the service between the store and the gateways. It takes payments, confirms them, gives money back, keeps a double-entry ledger, and checks every settlement file against what was sold.

The hard part is not the happy path. It is the boring failures: a call that times out after the card was charged, a webhook that comes twice or never, a customer with two tabs, a payout that is a little short.

## The seven rules

| # | Rule | Why |
|---|---|---|
| R1 | The gateway knows the truth about money. One function, *sync*, copies it into Mizan | One place to get right, and one place to test |
| R2 | A webhook is a hint. Mizan asks the gateway | Webhooks can be late, repeated, out of order, or fake |
| R3 | Mizan chooses the payment's id before the first call | A lost answer can be looked up by that id |
| R4 | One operation at a time per payment | No race between a capture and a refund |
| R5 | No gateway call inside a database transaction | A slow gateway does not hold locks |
| R6 | Each money fact is posted to the ledger once, in the same transaction as the fact | The books cannot drift from the payments |
| R7 | Money that cannot be explained goes to suspense | It stays visible until finance closes it |

## Questions and answers

| Question | Answer |
|---|---|
| The create call timed out. Was the customer charged? | Mizan does not guess. The operation was written down before the call (with Mizan's own id). The worker asks the gateway with that id. The gateway's record decides. A create is never sent twice. |
| The capture timed out. Do you send it again? | Only after a quiet minute, only once, and only when a fresh fetch shows it did not happen. A capture of a known amount is safe to repeat once. A card refund is not: the gateway takes no key for it, so Mizan never repeats it by itself. |
| A webhook comes twice, or before the API answer | Each webhook is saved once (unique per gateway event id) and only asks for a sync. Nothing in the webhook is trusted. |
| A webhook comes while a sync is already on its way | That sync may carry older news. A flag (`syncRequested`) is taken down before the fetch, and raised again by the webhook, so one more sync runs. One look too many is safe; one too few is not. |
| The customer pays the same order in two tabs | One order has one payment that counts. When a second one takes money, Mizan gives it back (void or refund) and tells the store. |
| The bank released the hold, then the store captured | The gateway's record wins. A capture that comes after the hold ended is a *late capture*: recorded, told to the store, and (by default) returned in full, once. |
| The gateway takes two seconds to answer. Does the worker fall behind? | It looks at 16 payments side by side (a setting), and the event sender sends 8 events side by side. This is cheap because of R5: no transaction and no database connection is held while a call waits. Each worker has its own thread, so a slow gateway does not stop matching or events. |
| Two workers take the same payment | `FOR UPDATE SKIP LOCKED` and a lease. The second worker does not see it. If the first one dies, the lease ends and another takes it. |
| Mizan crashes between the call and the save | The operation is `IN_FLIGHT` in the database. The next worker finds it and finishes it from the gateway's record. |
| The payout is short | Each line is matched to a payment, after one sync if it does not fit. A wrong amount, a line twice, a line nobody knows: each becomes a discrepancy, and its money goes to suspense. A wrong fee is posted as it is and reported. A payment that no file pays out is reported after three days. The ledger still balances. |
| The file is wrong as a whole | Wrong total, wrong line count, cut off: refused as a whole. Nothing is posted from a file Mizan cannot trust. |
| How does the store learn about changes? | Through signed events, from an outbox written in the same transaction as the change. Retried six times over about 33 hours, then `FAILED`, visible, and can be sent again by hand. |

## Choices and their price

| Choice | Instead of | Price paid |
|---|---|---|
| One service and PostgreSQL | Microservices and a message broker | Scaling past one database needs work. A store's volume does not need it |
| A worker that polls the database | A queue | A little delay (seconds). In return: one source of truth, and leases that survive a crash |
| Amounts in halalas, as `long` | Decimals | Every amount is a whole number. No rounding inside Mizan; fees round once, by a written rule |
| A ledger in the same database | A ledger service | The ledger is tied to this store. It is always in step with the payments |
| A simulator of both gateways | Mocks | More code to keep. In return: faults, webhooks and settlement files that behave like the real thing, and a book to compare with |
| A hand-written API description | Generated from the code | It can drift. A test compares it with the real routes |

## How it is proven

| Layer | What | Result |
|---|---|---|
| The rules | Unit and property tests of `mizan-core`, with generated cases | 334 tests, 15,000 generated cases, 99% of lines covered |
| The simulator | Its own tests | 73 tests |
| The service | Integration tests with PostgreSQL and the simulator: each flow, each fault, each race | 479 tests, 332 of them against PostgreSQL and the simulator; 96% of lines covered |
| Deliberate bugs | A bug is planted, the tests must fail, the bug is removed | 456 planted. 441 caught. 14 showed code that did nothing, which was deleted. 1 changed nothing |
| The proof run | A seeded plan of payments and faults. At the end Mizan's books equal the simulator's book | 150 orders with every build. Two runs of 10,000 orders with over 6,000 faults each, one of them with the workers' rounds side by side: every check passed |
| A real gateway's test mode | Needs test keys | Not run yet: assumptions A1 to A6 of the [design](DESIGN.md#15-assumptions-to-confirm) stay open |

## What is not done

| Topic | Today | Next step |
|---|---|---|
| A real gateway | The simulator follows public documents | Run layer 4 with test keys; each difference becomes a simulator fault and a test |
| Chargebacks | A dispute line is money Mizan cannot explain (suspense) | A dispute flow |
| More than one currency, saved cards, split payments | Not in scope | Each is a new design, not a patch |
| Limits across instances | Counted per instance | A shared counter, if more than one instance runs |
