# Proof run

| | |
|---|---|
| Seed | 7 |
| Orders | 2000 |
| Payments | 2068 |
| Rounds | 1 at a time |
| Took | 138 s |

## Where the payments ended

| State | Payments |
|---|---|
| CANCELED | 157 |
| CAPTURED | 1343 |
| EXPIRED | 162 |
| FAILED | 406 |
| of which late captures, returned | 58 |

## Faults planted

| Fault | Times |
|---|---|
| CAPTURE ERROR_500 | 8 |
| CAPTURE ERROR_500_AFTER | 7 |
| CAPTURE GARBAGE | 2 |
| CAPTURE LOST_ANSWER | 6 |
| CAPTURE TIMEOUT_BEFORE | 3 |
| CAPTURE VERY_SLOW_APPLY | 3 |
| CREATE ERROR_500 | 45 |
| CREATE ERROR_500_AFTER | 52 |
| CREATE GARBAGE | 50 |
| CREATE LOST_ANSWER | 51 |
| CREATE SLOW_APPLY | 48 |
| CREATE TIMEOUT_BEFORE | 49 |
| CREATE VERY_SLOW_APPLY | 39 |
| DASHBOARD_REFUND | 54 |
| REFUND ERROR_500 | 9 |
| REFUND ERROR_500_AFTER | 8 |
| REFUND GARBAGE | 10 |
| REFUND LOST_ANSWER | 14 |
| REFUND TIMEOUT_BEFORE | 9 |
| REFUND VERY_SLOW_APPLY | 15 |
| TWO_TABS | 68 |
| VOID ERROR_500 | 5 |
| VOID ERROR_500_AFTER | 4 |
| VOID GARBAGE | 4 |
| VOID LOST_ANSWER | 8 |
| VOID TIMEOUT_BEFORE | 5 |
| VOID VERY_SLOW_APPLY | 2 |
| WEBHOOK_DROP | 88 |
| WEBHOOK_DUP | 86 |
| WEBHOOK_EARLY | 85 |
| WEBHOOK_FORGED | 82 |
| WEBHOOK_LATE | 69 |
| WEBHOOK_REORDER | 81 |
| file: CHARGEBACK_LINE | 29 |
| file: DUPLICATE_LINE | 29 |
| file: EXTRA_LINE | 1 |
| file: MISSING_LINE | 35 |
| file: SHORT_AMOUNT | 35 |
| file: WRONG_AMOUNT | 35 |
| file: WRONG_FEE | 35 |

## Checks

| Check | Result |
|---|---|
| Each payment's captured and refunded totals equal the simulator's book | 2068 of 2068 |
| No card charged twice; an order keeps one payment's money at most; every late capture returned | Passed |
| No operation in flight, no payment waiting, every event delivered | Passed |
| The ledger balances; receivable, sales, suspense and bank agree (I1 to I4) | Passed |
| Every planted settlement error reported as itself, and nothing else | 234 discrepancies, all expected |
| A payout with a wrong total refused whole | Passed |
