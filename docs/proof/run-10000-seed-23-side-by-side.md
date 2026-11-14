# Proof run

| | |
|---|---|
| Seed | 23 |
| Orders | 10000 |
| Payments | 10306 |
| Rounds | 8 at a time |
| Took | 594 s |

## Where the payments ended

| State | Payments |
|---|---|
| CANCELED | 692 |
| CAPTURED | 6903 |
| EXPIRED | 772 |
| FAILED | 1939 |
| of which late captures, returned | 304 |

## Faults planted

| Fault | Times |
|---|---|
| CAPTURE ERROR_500 | 37 |
| CAPTURE ERROR_500_AFTER | 39 |
| CAPTURE GARBAGE | 38 |
| CAPTURE LOST_ANSWER | 33 |
| CAPTURE TIMEOUT_BEFORE | 41 |
| CAPTURE VERY_SLOW_APPLY | 26 |
| CREATE ERROR_500 | 221 |
| CREATE ERROR_500_AFTER | 227 |
| CREATE GARBAGE | 223 |
| CREATE LOST_ANSWER | 203 |
| CREATE SLOW_APPLY | 214 |
| CREATE TIMEOUT_BEFORE | 209 |
| CREATE VERY_SLOW_APPLY | 201 |
| DASHBOARD_REFUND | 232 |
| REFUND ERROR_500 | 55 |
| REFUND ERROR_500_AFTER | 58 |
| REFUND GARBAGE | 57 |
| REFUND LOST_ANSWER | 64 |
| REFUND TIMEOUT_BEFORE | 51 |
| REFUND VERY_SLOW_APPLY | 45 |
| TWO_TABS | 306 |
| VOID ERROR_500 | 22 |
| VOID ERROR_500_AFTER | 14 |
| VOID GARBAGE | 14 |
| VOID LOST_ANSWER | 12 |
| VOID TIMEOUT_BEFORE | 13 |
| VOID VERY_SLOW_APPLY | 17 |
| WEBHOOK_DROP | 421 |
| WEBHOOK_DUP | 425 |
| WEBHOOK_EARLY | 397 |
| WEBHOOK_FORGED | 436 |
| WEBHOOK_LATE | 423 |
| WEBHOOK_REORDER | 403 |
| file: CHARGEBACK_LINE | 149 |
| file: DUPLICATE_LINE | 149 |
| file: EXTRA_LINE | 1 |
| file: MISSING_LINE | 178 |
| file: SHORT_AMOUNT | 178 |
| file: WRONG_AMOUNT | 178 |
| file: WRONG_FEE | 178 |

## Checks

| Check | Result |
|---|---|
| Each payment's captured and refunded totals equal the simulator's book | 10306 of 10306 |
| No card charged twice; an order keeps one payment's money at most; every late capture returned | Passed |
| No operation in flight, no payment waiting, every event delivered | Passed |
| The ledger balances; receivable, sales, suspense and bank agree (I1 to I4) | Passed |
| Every planted settlement error reported as itself, and nothing else | 1189 discrepancies, all expected |
| A payout with a wrong total refused whole | Passed |
