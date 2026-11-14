# Proof runs

The proof run (`ProofRunIT`) makes many payments, each with faults drawn from a seed, takes each through its whole life and its settlement, and then compares Mizan's record with the simulator's own book. See the [design](../DESIGN.md#14-testing-and-proof).

**New to it?** [How the proof run works](HOW-IT-WORKS.md) explains the plan, the faults, the checks, how to read a report, and what the run does not prove.

Every build runs it with 150 orders. A longer run:

```bash
./mvnw verify -pl mizan-app -am -Dtest=none -Dit.test=ProofRunIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true -Dmizan.proof.payments=10000 -Dmizan.proof.seed=11
```

The same seed gives the same plan. The report is written to `mizan-app/target/proof/report.md`.

| Report | Orders | Payments | Faults | Rounds | Took | Result |
|---|---|---|---|---|---|---|
| [Seed 7](run-2000-seed-7.md) | 2,000 | 2,068 | 1,268 | 1 at a time | 138 s | Every check passed |
| [Seed 11](run-10000-seed-11.md) | 10,000 | 10,333 | 6,252 | 1 at a time | 613 s | Every check passed |
| [Seed 23](run-10000-seed-23-side-by-side.md) | 10,000 | 10,306 | 6,188 | 8 at a time | 594 s | Every check passed |

"Rounds" is how many payments the sync worker looks at side by side, and how many events the sender sends side by side (`-Dmizan.test.parallel`). The service's own default is 16 and 8.
