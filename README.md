# Mizan

**The payments service of an online store.** Take a payment by card, Apple Pay or pay later; confirm it; give money back; keep the books; and check that the money the gateway paid out is the money the store sold.

*Mizan* (ميزان) is Arabic for a scale. It is a working name.

> **Status:** design finished and reviewed. Code is being built. See [docs/plan/STATUS.md](docs/plan/STATUS.md).

## The idea in one minute

Payments go wrong in boring ways. A call times out after the card was charged. A webhook comes twice, or never. The customer pays in two tabs. The gateway pays out a little less than expected and nobody notices.

Mizan is built on seven rules that make these cases safe:

| # | Rule |
|---|---|
| R1 | The gateway knows the truth about money. Mizan keeps a copy, and one function, *sync*, updates it |
| R2 | A webhook is a hint, not a fact. Mizan asks the gateway |
| R3 | Mizan chooses the payment's id before the first call, so a lost answer can be looked up |
| R4 | One operation at a time per payment |
| R5 | No gateway call inside a database transaction |
| R6 | Every money fact is posted to the ledger once, with the fact |
| R7 | Money that cannot be explained goes to suspense, and stays visible |

The rules are not only claimed. A simulator breaks things on purpose, and a proof run checks the result against the simulator's own record.

## Documents

| Document | What it is |
|---|---|
| [Design](docs/DESIGN.md) | The high-level design: context, rules, flows, ledger, reconciliation, security, testing |
| [Low-level designs](docs/lld/README.md) | Each part in detail, with every case |
| [Decisions](docs/adr/README.md) | Why each important choice was made |
| [Glossary](docs/GLOSSARY.md) | The words, in English and Arabic |
| [Plan](docs/plan/PLAN.md) and [status](docs/plan/STATUS.md) | The milestones and where the work stands |
| [Reviews](docs/reviews) | What each review found and what was fixed |
| [Contributing](CONTRIBUTING.md) | The style of the docs and the code, and the review checklist |

## Not a real gateway

Mizan is an independent open-source project. It is not made or approved by any payment gateway or pay-later provider. Its simulator follows their public documents and is not their software.

## License

MIT. See [LICENSE](LICENSE).
