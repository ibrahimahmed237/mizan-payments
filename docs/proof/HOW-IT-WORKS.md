# How the proof run works

The README says: "a proof run of 10,000 orders with over 6,000 faults ends with Mizan's books equal to the gateway's". This page explains what that sentence means, how the run works, how to read its report, and what it does not prove.

The code is one test: [`ProofRunIT`](../../mizan-app/src/test/java/dev/mizan/app/proof/ProofRunIT.java).

## In one paragraph

The run creates thousands of orders against the real Mizan service (real code, real PostgreSQL) and a simulator of the two gateways. Before it starts, it draws a plan from a seed: what each customer does, what the store does, and which calls, webhooks and payout files go wrong. It plays every payment through its whole life, moves the clock forward by days, and then asks one question: **does Mizan's record of the money equal what really happened at the gateways?** The simulator keeps its own book of every charge and refund, so the answer is a comparison, not an opinion.

## Why it exists

The other tests check one flow or one failure at a time: a lost answer on a capture, a webhook that comes twice. Real trouble comes from mixtures: a lost answer on the create, then a late webhook, then a refund made by hand in the gateway's dashboard, then a payout file with a wrong fee. Nobody can write a test for every mixture. The proof run makes thousands of them at random and checks the end result for all of them.

## The players

| Player | What it is |
|---|---|
| Mizan | The real service, with its API, workers and ledger, on a real PostgreSQL (Testcontainers) |
| The simulator | A fake card gateway and a fake pay-later provider, with the statuses, webhooks and payout files the real ones document. It fails on purpose when told to |
| The simulator's book | Every charge, capture and refund the simulator really did. This is the truth the run compares against ([ADR 0010](../adr/0010-a-simulator-with-its-own-book.md)) |
| The clock | A clock the test moves by hand, so a run can cover days of gateway time in minutes |

## Step by step

### 1. The plan

From the seed, for each order:

| Choice | How often |
|---|---|
| Pay later instead of a card | 30% |
| Manual capture (hold now, capture later) instead of automatic | 30% |
| Amount | 10 to 510 riyals |
| Card | Most approve at once. Some need the customer on the bank's page (3-D Secure). Some are declined (no funds) |
| What the customer does on the bank's or provider's page, when the payment needs them | Approves 70%, declines 12%, leaves 10%, approves too late 8% |
| A fault on the create call | 15% |
| A webhook fault that stays on the order | 25% |

The same seed gives the same plan, so a failure can be run again exactly.

### 2. The payments' lives

The run then plays, for all orders at once:

1. **Create.** The store asks Mizan for each payment. Some customers open **two tabs** and pay twice for the same order.
2. **The customer acts** on the bank's or the provider's page.
3. **The store acts** on payments that are on hold: it captures 60% (some only in part), cancels 30%, and forgets 10%. Some of these calls get a fault.
4. **Refunds.** The store refunds about a quarter of captured payments, in full or in part, some with a fault. In about 4%, someone refunds by hand in the gateway's dashboard, without telling Mizan.
5. **Time passes.** The waiting time of card payments ends. The customers who approve too late do so now, after Mizan has told the store the payment ended.
6. **A day passes.** Holds that nobody captured end by themselves.

After each step the clock moves and the workers run until nothing is left to do, as their timers would run them.

### 3. The settlement

The gateways then pay out, with errors planted in the payout files:

1. A first card payout and a pay-later payout. Errors in the first: a wrong amount, a short amount, a wrong fee, a missing line, and one line for a payment that does not exist.
2. A second card payout with a line that was already settled (a duplicate), and a chargeback line.
3. A payout whose total does not add up. It must be refused whole.
4. Three days later, the daily check looks for captures that no payout ever settled.

### 4. The checks

| Check | What it means |
|---|---|
| Totals equal the book | For every payment, Mizan's captured and refunded amounts equal the simulator's. One payment wrong fails the run |
| Nobody paid twice | No card was charged twice, an order keeps the money of one payment at most, and every late capture went back to the customer |
| Nothing half done | No call still in flight, no payment still waiting, every event delivered to the store |
| The books balance | Debits equal credits, and receivable, sales, suspense and bank agree (I1 to I4 in [the ledger](../lld/ledger.md)) |
| Every planted payout error found | Each one reported as what it is, and nothing else reported. No false alarms |
| A broken payout refused | The file with the wrong total is refused whole, not half imported |

## The faults

Each one is a way the real world goes wrong. The run counts how many times each was planted.

### On a call (create, capture, void, refund)

| Fault | What happens |
|---|---|
| `LOST_ANSWER` | The gateway does the work, then the connection drops. The money moved; Mizan does not know |
| `TIMEOUT_BEFORE` | The gateway waits past Mizan's timeout and does nothing |
| `SLOW_APPLY` | Mizan times out, and the gateway does the work a few seconds later |
| `VERY_SLOW_APPLY` | The same, two minutes later: after Mizan has stopped waiting |
| `ERROR_500` | The gateway answers 500 without doing the work |
| `ERROR_500_AFTER` | The gateway does the work, then answers 500 |
| `GARBAGE` | The gateway does the work, then answers 200 with a body that is not the record |

### On webhooks (they stay on the order)

| Fault | What happens |
|---|---|
| `WEBHOOK_DROP` | No webhook ever comes |
| `WEBHOOK_DUP` | Every webhook comes three times |
| `WEBHOOK_LATE` | Every webhook comes five minutes late |
| `WEBHOOK_REORDER` | Later events arrive before earlier ones |
| `WEBHOOK_EARLY` | The webhook arrives before the call's answer |
| `WEBHOOK_FORGED` | Someone also sends a webhook with a wrong secret, and one with the right secret that claims "paid" |

### In payout files

| Fault | What happens |
|---|---|
| `WRONG_AMOUNT` | A line settles another amount than was captured |
| `SHORT_AMOUNT` | A line settles less |
| `WRONG_FEE` | The fee is not the agreed one |
| `MISSING_LINE` | A capture is never settled |
| `DUPLICATE_LINE` | A line settles a capture that was already settled |
| `CHARGEBACK_LINE` | A line of a kind Mizan does not handle by itself |
| `EXTRA_LINE` | A line for a payment that does not exist |
| `BAD_TOTAL` | The file's total does not equal its lines |

### Others

| Fault | What happens |
|---|---|
| `TWO_TABS` | The customer pays for the same order in two tabs |
| `DASHBOARD_REFUND` | Someone refunds in the gateway's dashboard, outside Mizan |

## Reading the report

Take the [run of seed 11](run-10000-seed-11.md):

| Line | Meaning |
|---|---|
| Orders 10,000, payments 10,333 | The 333 extra payments are the second tabs |
| Faults 6,252 | The sum of the fault table: about 0.6 faults per order |
| Where the payments ended | 6,927 captured, 1,946 failed, 767 expired, 693 canceled. Nothing left pending or on hold |
| Late captures, returned: 334 | Payments whose money arrived after Mizan had said they ended (late customers, very slow gateways). Each was recorded, then refunded in full. They are counted among the captured ones |
| 1,191 discrepancies, all expected | Every payout error planted was reported, and only those |
| Took 613 s | Ten minutes of real time, for days of gateway time |

## What it does not prove

| Limit | Why |
|---|---|
| The simulator is not a real gateway | It follows the gateways' public documents and is strict where they are silent. A real gateway's test mode is layer 4 of the proof, and it waits for test keys (see [the status](../plan/STATUS.md)) |
| One instance of Mizan | Two instances taking the same rows is tested elsewhere (`WorkerIT`, `MatchingIT`), not in this run |
| Load | The run checks correctness, not speed. Ten thousand orders in ten minutes is not a load test |
| Random, not every case | A seed covers many mixtures, not all of them. Each bug a mixture finds becomes a fixed test of its own |

## Running it

Every build runs 150 orders. A long run:

```bash
./mvnw verify -pl mizan-app -am -Dtest=none -Dit.test=ProofRunIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true -Dmizan.proof.payments=10000 -Dmizan.proof.seed=11
```

| Setting | Meaning |
|---|---|
| `-Dmizan.proof.payments` | How many orders (default 150) |
| `-Dmizan.proof.seed` | Which plan. The same seed gives the same plan |
| `-Dmizan.test.parallel` | How many payments the workers handle side by side (default 1 in tests; the service's own default is 16 and 8) |

The report is written to `mizan-app/target/proof/report.md`. When a check fails, its message names the order, the payment and the faults that order had, so the same seed shows the same failure again.

## In one sentence, for an interview

"I wrote a simulator of the gateways that fails on purpose and keeps its own book, then ran ten thousand orders through Mizan with about six thousand planted failures, lost answers, duplicate and forged webhooks, wrong payout files, and at the end every payment's money in Mizan equaled the simulator's book, nobody was charged twice, and every payout error was reported and nothing else."
