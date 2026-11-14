# Review 002 — M0 and M1: the build and the rules

- **Date:** 2026-11-14
- **Scope:** the Maven build and CI (M0); all of `mizan-core` (M1): money, the payment, sync, timers, operation rules, command checks, the schedule, ledger entries, fees, the settlement parser and matching.
- **Method:** the [review checklist](../../CONTRIBUTING.md#review-checklist), read as a critic; then 107 deliberate bugs, each planted alone, with the tests run against it.

## Built

| Part | What it is |
|---|---|
| `Sync.decide` | One pure function in six steps. Every cell of the design's tables is a test |
| `Timers` | Expiry of a waiting payment and of a hold, on fresh facts only |
| `OperationRules`, `CommandRules` | What to do with an unknown result; the checks before capture, cancel, refund and a new payment for an order |
| `SyncSchedule` | When the worker looks at a payment next |
| `Entries`, `FeeRule` | The entry of each money fact, built from signed amounts so one rule covers captures, refunds and returned fees; the fee formula |
| `SettlementFileParser`, `Matcher` | The neutral file format with whole-file checks; the match decision for a line |

311 tests. Line coverage of `mizan-core`: 99.8% (the build fails under 95%). A compiler warning fails the build.

## Findings

| # | Area | Finding | Fix |
|---|---|---|---|
| 1 | Simplicity | `Sync` had a special branch for "a status on the same step as the last one". An experiment removed it and no test failed. Reading the code again showed why: the steps below it already do nothing in every such case. | The branch is gone. One comment says why the cases are safe. |
| 2 | Simplicity | `Sync` skipped the status step for a captured payment with its own check. Also redundant: a captured payment's last gateway status is `PAID`, so every other status is already "old". | The check is gone. |
| 3 | Simplicity | The file parser refused a line longer than 512 characters. The check ran after the line was read, so it protected nothing, and a real over-long line is refused anyway for its fields. | The check is gone. The 50 MB limit on the upload is the real guard (M5). |
| 4 | Design | The design said `Money` is a type that amounts are made of. In the code that made every rule harder to read, for no safety: the rules never convert. | Amounts are `long` halalas. `Money` holds the two conversions between text and halalas, and reads `"150.00"` exactly or refuses. [ADR-0007](../adr/0007-money-in-halalas.md) says so. |
| 5 | Design | A failed operation's event for the store was added in two places in two ways. | One rule: the event follows from the finished operation (`EventType.forOperation`). `Sync` and `OperationRules` only say that the operation finished. |
| 6 | Tests | The property test class was named `SyncProperties`, and the build runs only classes named `*Test`. The properties did not run at first. | Renamed. The build log is checked for the four property runs (15,000 generated cases). |
| 7 | Tests | A top-level test next to nested test classes was not counted by the build. | Moved to its own class. |

## Experiments

107 deliberate bugs.

| Area | Bugs | Caught |
|---|---|---|
| Sync: amount check, money taken, money given back | 15 | 15 |
| Sync: status, old records, follow-ups, operation, memory | 24 | 22 |
| Timers | 7 | 7 |
| Operation rules | 6 | 6 |
| Command checks | 12 | 12 |
| Schedule | 5 | 5 |
| Ledger entries | 7 | 7 |
| Fees | 3 | 3 |
| Settlement parser | 12 | 11 |
| Matching | 8 | 8 |
| Money, payment and snapshot limits, status steps | 8 | 8 |

Three bugs were not caught. Each one pointed at code that did nothing (findings 1 to 3), and the answer was to delete the code, not to add a test.

## Checked and kept

| Topic | Why it holds |
|---|---|
| The rules use only the JDK and never read the clock | Architecture tests |
| A payment or a gateway record that breaks `0 ≤ refunded ≤ captured ≤ amount` | Cannot be built: the constructors refuse it |
| Properties P1 to P7 of sync | 15,000 generated cases per build |
| An unbalanced ledger entry | Cannot be built |
| A settlement file with a wrong total, count, sign or sum | Refused as a whole |

## Result

M0 and M1 are done. Next: the simulator (M2).
