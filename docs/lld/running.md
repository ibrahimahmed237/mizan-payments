# Security, configuration and metrics

## Security details

| Topic | Design |
|---|---|
| API keys | 32 random bytes, shown once when made. Stored as a SHA-256 hash. Looked up by the hash, so there is no comparison that leaks timing |
| Making keys | A command of the service, not an API: `keys create --name=website --scopes=payments` and `keys revoke --name=website`. Nobody can make a key over the network. The command runs against the database and stops; it starts no API and no workers |
| Tokens | A card or wallet token lives only in memory during the create call. It is not in the database, the logs, the idempotency record or any event |
| Logs | A filter removes `Authorization`, tokens and secrets. Bodies are not logged |
| Webhook secrets | Compared in constant time. Each gateway has its own |
| The return address | `return_url` must start with a configured address. Checked when the payment is created |
| The store's event URL | Comes from configuration, not from requests, so a caller cannot point Mizan at another server |
| Limits | 100 requests a second per API key, with a burst of 200. Webhooks: 200 a second per gateway, burst 400. The return page: 20 a second per address, burst 40. Bodies: 64 KB, settlement files 50 MB. Limits are counted per instance, on real time. Over a limit: `429 too_many_requests` with `Retry-After`; a body that says it is too large: `413 body_too_large`, before it is read |
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
| `MIZAN_PORT` | 8080 | The port of the API |
| `MIZAN_RETURN_URL_PREFIXES` | — | Allowed starts of `return_url`, separated by commas. A prefix must end at a border of the address: `https://store.example/orders` allows `https://store.example/orders/1` and not `https://store.example/ordersX` |
| `MIZAN_STORE_EVENT_URL`, `MIZAN_STORE_EVENT_SECRET` | empty | Where events go. Empty: events are kept and marked `SKIPPED`, not sent. With an address, the secret is needed |
| `mizan.payment.max-amount` | 10000000 | Halalas |
| `mizan.payment.card-wait` | 30m | See [workers](workers.md#times) |
| `mizan.payment.pay-later-wait` | 2h | |
| `mizan.payment.hold-window` | 7d | |
| `mizan.payment.late-capture` | `REFUND` | Or `KEEP` |
| `mizan.operation.quiet-time` | 1m | See [payments](payments.md#when-the-result-is-not-known). Must be longer than `mizan.http.timeout`, or the service does not start |
| `mizan.operation.idempotency-keys-kept` | 30d | How long an `Idempotency-Key` is remembered |
| `mizan.http.connect-timeout`, `mizan.http.timeout` | 2s, 10s | Time limits of a gateway call |
| `mizan.settlement.missing-after` | 3d | How long a gateway may take to pay out a capture or take back a refund |
| `mizan.fees.card.percent-bps`, `.fixed`, `.vat-bps` | 250, 100, 1500 | The card gateway's fee: 2.5% + 1 SAR, plus 15% VAT on the fee. Example values: set them from the contract |
| `mizan.fees.pay-later.percent-bps`, `.fixed`, `.vat-bps` | 400, 0, 1500 | The pay-later provider's fee. Example values |
| `mizan.workers.enabled` | true | Tests switch workers off to control time |
| `mizan.workers.sync-every`, `mizan.workers.match-every` | 1s | The pause between two rounds of the sync worker and of the matching worker |
| `mizan.workers.missing-check-cron` | `0 30 5 * * *` | When the daily settlement check runs, in UTC |
| `mizan.workers.send-every` | 1s | The pause between two rounds of the event sender |
| `mizan.workers.clean-up-cron` | `0 10 4 * * *` | When the daily clean-up runs, in UTC |
| `mizan.limits.api-per-second`, `.api-burst` | 100, 200 | Per API key |
| `mizan.limits.webhooks-per-second`, `.webhooks-burst` | 200, 400 | Per gateway |
| `mizan.limits.returns-per-second`, `.returns-burst` | 20, 40 | Per address, for the customer's return |

The service refuses to start when a needed setting is missing, and says which.

## Metrics

Served at `/actuator/prometheus`, with health at `/actuator/health/liveness` and `/actuator/health/readiness` (the database answers). Nothing else of the actuator is served. Keep these off the public address: a load balancer should pass only `/v1/...` and `/v3/api-docs`.

| Metric | Meaning | Worry when |
|---|---|---|
| `mizan.payments` by `state` | Payments in each state | — |
| `mizan.payments.pending.oldest.seconds` | Age of the oldest `PENDING` payment | Above the waiting time: the worker is stuck |
| `mizan.operations.in_flight` | Operations with no result | Growing |
| `mizan.gateway.calls` by `gateway`, `call`, `result` | Calls; `result` is `done`, `refused` or `unknown` | `unknown` rises: the gateway has trouble |
| `mizan.gateway.call.seconds` | Call time, by the same tags | Near the timeout |
| `mizan.webhooks` by `gateway`, `result` | `accepted`, `repeated`, `unmatched`, or the refusal's code (`unauthorized`, `malformed_body`) | `unauthorized` rises: a wrong secret, or an attack |
| `mizan.late_captures` | Payments whose money was taken after they had ended | Any new one |
| `mizan.outbox.waiting` | Events not delivered yet | Growing |
| `mizan.discrepancies.open` | Open differences | Any |
| `mizan.ledger.suspense` | Suspense balance in halalas | Not zero |

The numbers about state are read from the database each time they are scraped, so every instance shows the same.

## Running it on one machine

`docker-compose.yml` runs Mizan, PostgreSQL and the simulator. It is for trying Mizan out, never for real money.

```bash
./mvnw package -DskipTests && docker compose up --build -d && docker compose run --rm mizan keys create --name=demo --scopes=payments,backoffice
```

The API is then on `http://localhost:8080`, its description on `http://localhost:8080/v3/api-docs`, and the simulator on `http://localhost:8090`. The image runs as a user without rights, with a heap of 75% of the container's memory.

## Tests

| Test | Proves |
|---|---|
| Key tests | A key is shown once; the hash is stored; a revoked key is refused |
| Log tests | A token and a secret never appear in the log output of a full flow |
| Limit tests | The request limit, the body limit, `Retry-After` |
| Start-up tests | A missing setting stops the start with a clear message |
| Metric tests | Each counted metric moves when its thing happens; the state gauges read the database; only health and metrics are served by the actuator |
| Clean-up tests | Old keys, webhooks and delivered events go; payments, the ledger, files, waiting and failed events stay |
