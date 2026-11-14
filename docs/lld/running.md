# Security, configuration and metrics

## Security details

| Topic | Design |
|---|---|
| API keys | 32 random bytes, shown once when made. Stored as a SHA-256 hash. Looked up by the hash, so there is no comparison that leaks timing |
| Making keys | A command of the service (`mizan keys create --name --scopes`), not an API. Nobody can make a key over the network |
| Tokens | A card or wallet token lives only in memory during the create call. It is not in the database, the logs, the idempotency record or any event |
| Logs | A filter removes `Authorization`, tokens and secrets. Bodies are not logged |
| Webhook secrets | Compared in constant time. Each gateway has its own |
| The return address | `return_url` must start with a configured address. Checked when the payment is created |
| The store's event URL | Comes from configuration, not from requests, so a caller cannot point Mizan at another server |
| Limits | 100 requests a second per API key, with a burst of 200. Webhooks: 200 a second per gateway. The return page: 20 a second per address. Bodies: 64 KB, settlement files 50 MB. Limits are counted per instance |
| Input | Unknown JSON fields are refused. Text fields have a maximum length. Ids must be UUIDs |
| Transport | TLS ends at the load balancer in front of Mizan. Mizan sends `Strict-Transport-Security` and no content that a browser renders |

## Configuration

Defaults are in `application.yml`. The environment overrides them. Secrets have no default.

| Setting | Default | Meaning |
|---|---|---|
| `MIZAN_DB_URL`, `MIZAN_DB_USER`, `MIZAN_DB_PASSWORD` | — | The database |
| `MIZAN_CARD_GATEWAY_URL`, `MIZAN_CARD_GATEWAY_KEY` | — | The card gateway |
| `MIZAN_CARD_GATEWAY_WEBHOOK_SECRET` | — | Checks its webhooks |
| `MIZAN_PAY_LATER_URL`, `MIZAN_PAY_LATER_KEY`, `MIZAN_PAY_LATER_WEBHOOK_SECRET` | — | The pay-later provider |
| `MIZAN_PUBLIC_URL` | — | Mizan's own address, for the customer's return |
| `MIZAN_RETURN_URL_PREFIXES` | — | Allowed starts of `return_url` |
| `MIZAN_STORE_EVENT_URL`, `MIZAN_STORE_EVENT_SECRET` | empty | Where events go. Empty: events are kept, not sent |
| `mizan.payment.max-amount` | 10000000 | Halalas |
| `mizan.payment.card-wait` | 30m | See [workers](workers.md#times) |
| `mizan.payment.pay-later-wait` | 2h | |
| `mizan.payment.hold-window` | 7d | |
| `mizan.payment.late-capture` | `REFUND` | Or `KEEP` |
| `mizan.operation.quiet-time` | 1m | See [payments](payments.md#when-the-result-is-not-known) |
| `mizan.settlement.missing-after` | 3d | |
| `mizan.fees.*` | Example values | The fee table. Set it from the contract |
| `mizan.workers.enabled` | true | Tests switch workers off to control time |

The service refuses to start when a needed setting is missing, and says which.

## Metrics

| Metric | Meaning | Worry when |
|---|---|---|
| `mizan.payments` by state and method | Counts | — |
| `mizan.payments.pending.oldest.seconds` | Age of the oldest `PENDING` payment | Above the waiting time: the worker is stuck |
| `mizan.operations.in_flight` | Operations with no result | Growing |
| `mizan.gateway.calls` by gateway, call and result | `done`, `refused`, `unknown` | `unknown` rises: the gateway has trouble |
| `mizan.gateway.call.seconds` | Call time | Near the timeout |
| `mizan.webhooks` by gateway and result | Accepted, repeated, refused, unmatched | Refused rises: wrong secret, or an attack |
| `mizan.late_captures` | Late captures | Any |
| `mizan.outbox.waiting` | Events not delivered yet | Growing |
| `mizan.discrepancies.open` by type | Open differences | Any |
| `mizan.ledger.suspense` | Suspense balance in halalas | Not zero |

Health: `/actuator/health/liveness` and `/actuator/health/readiness` (the database answers).

## Tests

| Test | Proves |
|---|---|
| Key tests | A key is shown once; the hash is stored; a revoked key is refused |
| Log tests | A token and a secret never appear in the log output of a full flow |
| Limit tests | The request limit, the body limit, `Retry-After` |
| Start-up tests | A missing setting stops the start with a clear message |
| Metric tests | Each metric moves when its thing happens |
