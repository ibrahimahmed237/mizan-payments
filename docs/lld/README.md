# Low-level designs

Each page describes one part: its model, its rules, its cases, and the tests that prove it. Start with [sync](sync.md); it is the heart.

| Page | Part |
|---|---|
| [sync.md](sync.md) | How the gateway's record of a payment is copied into Mizan. Every case |
| [payments.md](payments.md) | The payment, its operations (create, capture, cancel, refund), and their failure cases |
| [gateways.md](gateways.md) | The gateway interface, the two adapters, and the simulator with its faults |
| [workers.md](workers.md) | Webhooks in, the sync worker, timers |
| [ledger.md](ledger.md) | Accounts, entries, invariants |
| [reconciliation.md](reconciliation.md) | Settlement files, matching, discrepancies, suspense |
| [api.md](api.md) | Endpoints, idempotency, errors, events sent to the store |
| [data.md](data.md) | Tables, keys, indexes |
| [running.md](running.md) | Security details, configuration, metrics |
