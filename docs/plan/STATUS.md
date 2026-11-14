# Status

Where the work stands right now. This file is updated after every milestone.

## Now

| | |
|---|---|
| **Phase** | Building |
| **Done** | The design and its reviews; M0, M1 and M2 |
| **In progress** | M3 |
| **Next** | M4 |

See the milestones in [PLAN.md](PLAN.md).

## Milestones

| # | Milestone | State | Review |
|---|---|---|---|
| — | Design | Done | [000](../reviews/000-design.md), [001](../reviews/001-design-second-review.md) |
| M0 | Foundation | Done | [002](../reviews/002-m0-m1-the-rules.md) |
| M1 | The rules | Done | [002](../reviews/002-m0-m1-the-rules.md) |
| M2 | The simulator | Done | [003](../reviews/003-m2-simulator.md) |
| M3 | Payments | In progress | |
| M4 | Webhooks and workers | Not started | |
| M5 | Reconciliation | Not started | |
| M6 | Events and hardening | Not started | |
| M7 | Proof | Not started | |

## Proof

| Layer | State |
|---|---|
| 1. The rules, with generated cases | Passing: 311 tests, properties P1 to P7 with 15,000 generated cases |
| 2. The service against the simulator | Not started |
| 3. The proof run | Not started |
| 4. A real gateway's test mode | Needs test keys from the owner |

## Assumptions

The six assumptions of the [design](../DESIGN.md#15-assumptions-to-confirm) are open until layer 4 runs.

## Log

| Date | What happened |
|---|---|
| 2026-11-14 | Idea chosen after a look at the Saudi market. Gateway documents read. Design written. |
| 2026-11-14 | Two design reviews: 20 findings fixed, among them a create that could not be sent again, false alarms on old records, and two payments for one order. |
| 2026-11-14 | M0 and M1 done: the build, CI, and all the rules in `mizan-core`. 311 tests, 107 deliberate bugs. |
| 2026-11-14 | M2 done: the simulator of both gateways, with its own book, 15 faults and 9 settlement file faults. 73 tests, 50 deliberate bugs. One of them found that every call took 44 ms; now 3 ms. |
