# Status

Where the work stands right now. This file is updated after every milestone.

## Now

| | |
|---|---|
| **Phase** | Built and reviewed. Ready to publish |
| **Done** | The design, M0 to M7, the final review, and a second look after it ([007](../reviews/007-second-final-review.md)) |
| **Open** | Layer 4 of the proof: a run against a real gateway's test mode, which needs test keys |

See the milestones in [PLAN.md](PLAN.md).

## Milestones

| # | Milestone | State | Review |
|---|---|---|---|
| — | Design | Done | [000](../reviews/000-design.md), [001](../reviews/001-design-second-review.md) |
| M0 | Foundation | Done | [002](../reviews/002-m0-m1-the-rules.md) |
| M1 | The rules | Done | [002](../reviews/002-m0-m1-the-rules.md) |
| M2 | The simulator | Done | [003](../reviews/003-m2-simulator.md) |
| M3 | Payments | Done | [004](../reviews/004-m3-m4-payments-and-workers.md) |
| M4 | Webhooks and workers | Done | [004](../reviews/004-m3-m4-payments-and-workers.md) |
| M5 | Reconciliation | Done | [005](../reviews/005-m5-m6-reconciliation-and-hardening.md) |
| M6 | Events and hardening | Done | [005](../reviews/005-m5-m6-reconciliation-and-hardening.md) |
| M7 | Proof | Done, except the real gateway run | [006](../reviews/006-final-review.md) |

## Proof

| Layer | State |
|---|---|
| 1. The rules, with generated cases | Passing: 334 tests in `mizan-core`, properties P1 to P7 with 15,000 generated cases. 99% of lines covered |
| 2. The service against the simulator | Passing: 473 tests in `mizan-app` (332 of them against PostgreSQL and the simulator; 2 skipped: the real gateway check), and 73 in `mizan-simulator`. 96% of the service's lines covered |
| 3. The proof run | Passing. 150 orders with every build; longer runs of [2,000](../proof/run-2000-seed-7.md) and [10,000](../proof/run-10000-seed-11.md) orders, and one of [10,000 with the workers' rounds side by side](../proof/run-10000-seed-23-side-by-side.md): all checks passed |
| 4. A real gateway's test mode | Built (`RealGatewayTest`), skipped without test keys. Not run yet |

Deliberate bugs, all milestones and the second look: 456 (119 in the rules, 50 in the simulator, 287 in the service). See each review for the ones that got past the tests at first and what changed.

## Assumptions

The six assumptions of the [design](../DESIGN.md#15-assumptions-to-confirm) are open until layer 4 runs. The simulator follows the gateways' public documents and is strict where they are silent.

## Log

| Date | What happened |
|---|---|
| 2026-11-14 | Idea chosen after a look at the Saudi market. Gateway documents read. Design written. |
| 2026-11-14 | Two design reviews: 20 findings fixed, among them a create that could not be sent again, false alarms on old records, and two payments for one order. |
| 2026-11-14 | M0 and M1 done: the build, CI, and all the rules in `mizan-core`. 311 tests, 107 deliberate bugs. |
| 2026-11-14 | M2 done: the simulator of both gateways, with its own book, 15 faults and 9 settlement file faults. 73 tests, 50 deliberate bugs. One of them found that every call took 44 ms; now 3 ms. |
| 2026-11-14 | M3 and M4 done: the API, idempotency, the ledger in the database, webhooks, the worker. 209 deliberate bugs. Found a race (a sibling payment canceled without its lock) and five checks that could never fail. |
| 2026-11-14 | M5 and M6 done: reconciliation, events, limits, metrics, Docker. 76 deliberate bugs. Found a daily check without locks, an event lease shorter than its round, and a body limit that a chunked body got around. |
| 2026-11-14 | M7 done: the proof run (up to 10,000 orders), the real gateway check, the demo, the interview page, and the final review. |
| 2026-11-14 | A second look at the finished project: nine findings fixed. The workers now run their rounds side by side, a "no" to a repeated call is no longer believed without the gateway's record, and made-up webhook addresses cost no memory. 14 more deliberate bugs. |
