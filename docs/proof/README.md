# Proof runs

The proof run (`ProofRunIT`) makes many payments, each with faults drawn from a seed, takes each through its whole life and its settlement, and then compares Mizan's record with the simulator's own book. See the [design](../DESIGN.md#14-testing-and-proof).

Every build runs it with 150 orders. A longer run:

```bash
./mvnw verify -pl mizan-app -am -Dtest=none -Dit.test=ProofRunIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true -Dmizan.proof.payments=10000 -Dmizan.proof.seed=11
```

The same seed gives the same plan. The report is written to `mizan-app/target/proof/report.md`.

| Report | Orders | Payments | Faults | Took | Result |
|---|---|---|---|---|---|
| [Seed 7](run-2000-seed-7.md) | 2,000 | 2,068 | 1,268 | 138 s | Every check passed |
| [Seed 11](run-10000-seed-11.md) | 10,000 | 10,333 | 6,252 | 613 s | Every check passed |
