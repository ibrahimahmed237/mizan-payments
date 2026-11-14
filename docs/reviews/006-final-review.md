# Review 006 — the final review

- **Date:** 2026-11-14
- **Scope:** the whole project, before it is published: every "done when" line of the [plan](../plan/PLAN.md), every claim of the [design](../DESIGN.md) and the low-level designs against the code, the proof, the docs, and the history.
- **Method:** read as a judge who wants to find what is wrong. Each claim was traced to the code or a test that shows it. Where none did, either the claim or the code changed.

## The plan's "done when" lines

| Milestone | Checked | Notes |
|---|---|---|
| M0 Foundation | All | The docs check and CI run on every push |
| M1 The rules | All | Coverage of `mizan-core` stays above 95% |
| M2 The simulator | All | — |
| M3 Payments | All | — |
| M4 Webhooks and workers | All | — |
| M5 Reconciliation | All | "A 200,000-line file is read as a stream" was not true as written and had no test. Now: a test takes such a file in one call, and the line says so ([005](005-m5-m6-reconciliation-and-hardening.md), finding 6) |
| M6 Events and hardening | All | "A slow store" had no test; now it has ([005](005-m5-m6-reconciliation-and-hardening.md), finding 5) |
| M7 Proof | All but one | The real gateway run needs test keys. The test is built and skips itself without them |

## Design claims against the code

| Claim | Where it is shown |
|---|---|
| No gateway call inside a transaction (R5) | `CaptureCancelIT`: while the gateway is silent, no transaction is open and the payment's row is free |
| One operation at a time (R4) | A partial unique index in the database; `RaceIT` sends a capture and a cancel together |
| Every money fact posted once, with the fact (R6) | `Books` checks I1 to I4 after every integration test; a failed change leaves no entry (`EventPayloadIT`) |
| Unexplained money in suspense (R7) | `ReconciliationIT`, every discrepancy type; the bank account always equals the lines' net amounts |
| Two instances never take the same row | `FOR UPDATE SKIP LOCKED` with leases; `WorkerIT` and `MatchingIT` run two workers at once |
| A crash at any point is finished by the worker | `CrashIT`: an operation left `IN_FLIGHT`. `WorkerIT` and `MatchingIT`: a worker that dies after its claim, or in the middle of a file |
| Logs hold no token, key or secret | `OperationsIT` runs the main flows and searches every log line |
| The API description matches the routes | `ApiDescriptionIT` compares `openapi.yaml` with the controllers |
| A file is refused as a whole | `ReconciliationIT`: wrong total, cut off, not a file, empty |

Claims that did not hold, and were changed (details in [005](005-m5-m6-reconciliation-and-hardening.md), finding 10, and below):

| Claim | What was true | Now |
|---|---|---|
| "Ledger entries, gateway events and settlement lines are never changed or deleted" | Entries: true, the database refuses it. Gateway events are deleted after 90 days, and a line's status changes | The design says each of the three plainly |
| "The daily check runs once a day and after each file" | Once a day only | The docs say once a day |
| "A filter removes secrets from the logs" | No such filter: headers and bodies are never logged | The docs say that |
| "Logs carry the request id" | The id was in the logging context, but never printed | Each log line now carries it |
| "API keys are compared in constant time" | They are looked up by hash, so there is no comparison | The design says that |
| "The event lease is longer than a call" | True, and beside the point: a round is 50 calls | The lease is longer than a round |

## Second look at the riskiest code

These were read again, slowly, after the experiments: the code that moves money or decides when it moved.

| Code | Question asked | Answer |
|---|---|---|
| `OperationRunner`, `AdvancePayment` | Can a call be sent twice, or decided on old facts? | No. The operation is committed before the call; a second call only after a fresh fetch shows the first did nothing, and only for a capture, a cancel, or a refund with a key |
| `PaymentWriter` | Can two writers lose each other's change? | No. Every write holds the payment's lock, taken in a fixed order (order, then payment) |
| `MatchLine`, `SettlementWriter` | Can a line be settled twice, or a payment's settled totals be lost? | No. The line and the payment are locked; a line is handled once; a second entry for a line is refused by the ledger's unique key |
| `CheckMissingSettlements` | Can it open a false alarm? | Not any more: it reads each payment under its lock ([005](005-m5-m6-reconciliation-and-hardening.md), finding 1) |
| `ReceiveWebhook` | Can a forged or repeated webhook move money? | No. It only asks for a look; the look asks the gateway |
| `LimitsFilter`, `ApiKeyFilter` | Can a caller get around a limit or a scope? | A chunked body could get around the body limit; fixed. Odd paths do not get around the scope; tested |

## The proof

| Run | Orders | Payments | Faults planted | Result |
|---|---|---|---|---|
| Every build | 150 | 153 | 80 | All checks passed, in 24 seconds |
| [Seed 7](../proof/run-2000-seed-7.md) | 2,000 | 2,068 | 1,268 | All checks passed, in 138 seconds |
| [Seed 11](../proof/run-10000-seed-11.md) | 10,000 | 10,333 | 6,252 | All checks passed, in 613 seconds. 334 late captures, all returned; 1,191 discrepancies, all of them planted |

Each run compares every payment's captured and refunded totals with the simulator's book, checks that no card was charged twice and that an order keeps one payment's money at most, that every late capture went back, that nothing is left in flight or undelivered, that the books balance, and that every planted settlement fault was reported as itself and nothing else.

## What is still open

| Topic | Why it is open | What closes it |
|---|---|---|
| Assumptions A1 to A6 | The simulator follows public documents, not the gateways themselves | `RealGatewayTest` with test keys; each difference becomes a simulator fault and a test |
| Limits per instance | Two instances allow twice the rate | A shared counter, if Mizan runs on more than one instance |
| Requests without a valid key | Not counted by the API limit | A load balancer with a limit per address, as [running](../lld/running.md) says |

## History

Every commit builds and passes its tests. Commits follow Conventional Commits, in the order the work was done. No secret, real key or real card number is in the history; the simulator's test keys are named as such.

## Result

The project is ready to publish. The one open item, the real gateway run, is stated in the README, the status page and the interview page.
