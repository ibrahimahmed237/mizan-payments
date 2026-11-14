# API

JSON over HTTPS. All paths start with `/v1`. The machine-readable description (OpenAPI 3.1, YAML) is served at `/v3/api-docs`; it is [written by hand](../../mizan-app/src/main/resources/openapi.yaml), and a test fails when it and the service's routes differ.

## Callers

| Scope | May call |
|---|---|
| `payments` | Payments, captures, cancels, refunds: create and read |
| `backoffice` | Settlements, discrepancies, the ledger, reports, events |

The key is sent as `Authorization: Bearer <key>`. No key or a wrong key: `401`. A key without the scope: `403`.

## Endpoints

| Method and path | Scope | Does |
|---|---|---|
| `POST /v1/payments` | payments | Creates a payment |
| `GET /v1/payments/{id}` | payments | Reads a payment, with its operations |
| `GET /v1/payments?reference=&status=&cursor=&limit=` | payments | Lists payments, newest first, in pages |
| `POST /v1/payments/{id}/capture` | payments | Captures a hold |
| `POST /v1/payments/{id}/cancel` | payments | Cancels |
| `POST /v1/payments/{id}/refunds` | payments | Refunds |
| `GET /v1/payments/{id}/refunds` | payments | Lists a payment's refunds |
| `POST /v1/payments/{id}/sync` | payments | Does now what the sync worker would do for this payment. Returns the payment |
| `POST /v1/webhooks/card`, `POST /v1/webhooks/pay-later` | — (gateway secret) | A webhook from a gateway ([workers](workers.md#a-webhook-comes-in)) |
| `GET /v1/return/{id}` | — | The customer comes back. `303` to the store ([workers](workers.md#the-customer-comes-back)) |
| `POST /v1/settlements` | backoffice | Uploads a settlement file: the body is the file ([reconciliation](reconciliation.md#step-1--accept-or-refuse-the-file)) |
| `GET /v1/settlements`, `GET /v1/settlements/{id}` | backoffice | Files and their progress, newest first |
| `GET /v1/discrepancies?status=&type=`, `GET /v1/discrepancies/{id}` | backoffice | Lists discrepancies, newest first. `status` is `OPEN` or `RESOLVED` |
| `POST /v1/discrepancies/{id}/retry`, `/accept` | backoffice | Closes a discrepancy ([reconciliation](reconciliation.md#discrepancies)) |
| `GET /v1/ledger/balances` | backoffice | Each account's debits, credits and balance, and whether the books balance |
| `GET /v1/ledger/entries?payment_id=` | backoffice | A payment's entries with their postings, oldest first |
| `GET /v1/reports/reconciliation` | backoffice | Unsettled money by age (up to 3 days, 3 to 7, over 7), the suspense balance, open discrepancies by type |
| `GET /v1/events?status=`, `POST /v1/events/{id}/redeliver` | backoffice | Events to the store |

## Create a payment

```json
POST /v1/payments
Idempotency-Key: order-1042-try-1

{
  "amount": 15000,
  "currency": "SAR",
  "reference": "order-1042",
  "method": "CARD",
  "source": { "token": "tok_..." },
  "capture": "AUTO",
  "return_url": "https://store.example/orders/1042/paid",
  "description": "Order 1042"
}
```

| Field | Rules |
|---|---|
| `amount` | Whole halalas, from 100 to the configured limit (default 10,000,000) |
| `currency` | `SAR` |
| `reference` | 1 to 64 characters: letters, digits, `-`, `_`, `.` |
| `method` | `CARD`, `APPLE_PAY`, `PAY_LATER` |
| `source.token` | Needed for `CARD` and `APPLE_PAY`. Not allowed for `PAY_LATER`. Never stored, never logged |
| `capture` | `AUTO` (default) or `MANUAL` |
| `return_url` | Needed for `CARD` and `PAY_LATER`. Must start with one of the configured addresses |
| `description` | Up to 255 characters |

```json
201 Created

{
  "id": "8d0f...",
  "status": "PENDING",
  "amount": 15000,
  "currency": "SAR",
  "reference": "order-1042",
  "method": "CARD",
  "capture": "AUTO",
  "captured_amount": 0,
  "refunded_amount": 0,
  "next_action": { "type": "redirect", "url": "https://..." },
  "pending_reason": null,
  "failure": null,
  "card": { "scheme": "mada", "last4": "3333" },
  "late": false,
  "version": 2,
  "created_at": "2026-11-14T09:00:00Z"
}
```

`GET /v1/payments/{id}` returns the same object with one more field, `operations`: everything that was done to the payment, oldest first.

| `status` | What the store does |
|---|---|
| `PENDING` with `next_action` | Send the customer to the URL |
| `PENDING` with `pending_reason: gateway_unknown` | Wait for the event, or read the payment again. The answer comes within about a minute. Until then a new payment for the same `reference` is refused |
| `AUTHORIZED` | Capture when the order ships |
| `CAPTURED` | Deliver |
| `FAILED` | Show `failure.message`. A new payment needs a new `Idempotency-Key` |

## List payments

```json
GET /v1/payments?reference=order-1042&limit=20

{ "data": [ { ...a payment... }, ... ], "next_cursor": "MjAyNi0xMS0x..." }
```

| Rule | Detail |
|---|---|
| Order | Newest first. Payments made at the same instant are ordered by id, so a page never repeats or skips one |
| `limit` | From 1 to 100. Default 20. A value outside is brought inside |
| `cursor` | Send `next_cursor` back to get the next page. `null` means this was the last page |
| Filters | `reference` and `status`, alone or together |

## Capture, cancel, refund

```json
POST /v1/payments/{id}/refunds
Idempotency-Key: order-1042-refund-1

{ "amount": 5000, "reason": "Returned one item" }
```

```json
201 Created            (202 Accepted when the result is not known yet)

{ "id": "...", "payment_id": "...", "type": "REFUND", "status": "SUCCEEDED",
  "amount": 5000, "reason": "Returned one item", "origin": "API", "failure": null }
```

An operation's `status` in the API is `PENDING` (in flight), `SUCCEEDED` or `FAILED`.

| Answer | Meaning |
|---|---|
| `201` with `SUCCEEDED` | Done |
| `201` with `FAILED` | The gateway said no. `failure.code` says why. Nothing changed |
| `202` with `PENDING` | Sent, and the result is not known yet. An event follows, or read the payment again |
| `200` with the header `Idempotent-Replay: true` | A repeat. The operation as it is now |

The body may be left out. A capture without `amount` takes the whole hold. A refund without `amount` gives back all that is left.

Before the rules are checked, Mizan asks the gateway for the payment's record. So a refund made in the gateway's dashboard counts, and a customer who paid a moment ago is seen.

## Idempotency

A key points to **the payment or the operation that the first request made**. A repeat returns that same thing, as it is now.

| Case | Answer |
|---|---|
| First request with a key | Runs. The key is saved with the id of what it made, in the same transaction |
| The same key and the same body, later | `200` with that payment or operation as it is now, and the header `Idempotent-Replay: true`. Nothing runs |
| The same key, another body | `422 idempotency_key_reused` |
| The same key at the same moment | The second waits for the first one's transaction, then is a repeat |
| A request that was refused (`4xx`) | Nothing was made, so the key is not saved. The same request runs again and is checked again |
| No key on a `POST` that moves money | `400 idempotency_key_required` |

- "The same body" is compared by a hash of the method, the path and the JSON, with object keys in order. Only the hash is stored, never the body, so a card token is not kept here either.
- A key is a string of 1 to 128 printable characters, without spaces. Keys are kept for 30 days.
- A key belongs to one request. The key of a payment cannot be used for a refund, and the other way round: `422`.
- No answer is stored, only the id. So a crash between "made" and "answered" loses nothing: the repeat finds the payment and shows its real state.

## Errors

`application/problem+json`:

```json
{ "type": "https://mizan.example/problems/refund_too_large",
  "title": "The amount must be above zero and at most what can still be refunded: 10000 halalas.",
  "status": 422, "code": "refund_too_large", "request_id": "..." }
```

`code` is for programs and does not change. `title` is for people. `request_id` is also in the header `X-Request-Id` of every answer; quote it when asking about a request.

| Status | Used for |
|---|---|
| `400` | A body that is not JSON, a field Mizan does not know, a value of the wrong kind (`"150"` or `150.5` for an amount), a missing header |
| `401`, `403` | No key, wrong key, revoked key; a key without the scope |
| `404` | No such payment, discrepancy, file. Also an id that is not a UUID |
| `409` | The payment's state does not allow it; an operation is in progress; the order has an unresolved or paid payment |
| `413` | A body that is too large |
| `422` | A field breaks a rule; an `Idempotency-Key` used for another request |
| `429` | Too many requests. `Retry-After` says when to come back |
| `500`, `503` | Mizan's own fault. Safe to send again with the same key |

A gateway that is down is **not** a `5xx`: the payment or operation is answered as `PENDING` (see the [design](../DESIGN.md#11-api-conventions)). There is one exception. Canceling a payment that waits for the customer needs the gateway's word that the customer has not paid. If the gateway cannot be asked, the answer is `503 gateway_unavailable`, nothing is changed, and the store tries again.

### Error codes

| Code | Status | When |
|---|---|---|
| `malformed_body` | 400 | The body cannot be read; `title` names the field |
| `idempotency_key_required`, `invalid_idempotency_key` | 400 | The header is missing or not 1 to 128 printable characters |
| `invalid_parameter` | 400 | A query parameter of the wrong kind |
| `unauthorized`, `forbidden` | 401, 403 | The key |
| `not_found` | 404 | Nothing at this address |
| `previous_payment_unresolved`, `reference_already_paid` | 409 | [One order, one payment](payments.md#one-order-one-payment-that-counts) |
| `operation_in_progress` | 409 | Another operation on the payment has not finished |
| `not_capturable`, `hold_expired` | 409 | Capture |
| `already_captured`, `not_cancelable` | 409 | Cancel |
| `not_refundable` | 409 | Refund |
| `invalid_amount`, `unsupported_currency`, `invalid_reference`, `invalid_method`, `invalid_capture` | 422 | Create |
| `source_required`, `source_not_allowed`, `return_url_required`, `return_url_not_allowed`, `description_too_long` | 422 | Create |
| `partial_capture_not_supported` | 422 | Capture |
| `refund_too_large`, `reason_too_long` | 422 | Refund |
| `idempotency_key_reused` | 422 | The key was used for another request |
| `invalid_status`, `invalid_cursor` | 422 | List |
| `gateway_unavailable` | 503 | Cancel of a waiting payment while the gateway cannot be asked |
| `body_too_large` | 413 | A request body over 64 KB |
| `file_too_large` | 413, 422 | A settlement file over 50 MB, or with more than 500,000 lines |
| `malformed_file`, `wrong_gateway`, `wrong_currency`, `line_count_mismatch`, `line_does_not_add_up`, `payout_total_mismatch` | 422 | A settlement file that is refused as a whole |
| `payout_exists` | 409 | The payout was imported before, with other content |
| `still_unmatched`, `not_retryable`, `already_resolved` | 409 | Closing a discrepancy |
| `already_waiting` | 409 | Redelivering an event that waits to be sent |
| `too_many_requests` | 429 | Over a limit. `Retry-After` says when to come back |
| `note_required` | 422 | Accepting a discrepancy without a note |

A payment or operation that failed is not an error answer. Its `failure.code` is one of: `declined` (the bank or the provider said no; `failure.message` has its words), `gateway_refused` (the gateway refused the request), `gateway_unreachable` (no answer came, and nothing was taken), `no_effect` (an operation that was sent, got no answer, and changed nothing), `hold_gone`, `already_captured`.

## Events sent to the store

Each change of a payment writes an event in the same transaction (the outbox). The event sender posts it to the configured URL.

```json
POST <the store's URL>
Mizan-Event-Id: 6b1f...
Mizan-Timestamp: 1794650000
Mizan-Signature: v1=5f2a...

{ "id": "6b1f...", "type": "payment.captured", "created_at": "...",
  "data": { "payment": { ...the payment as the API returns it... }, "operation": null } }
```

| Rule | Detail |
|---|---|
| Signature | `hex(HMAC-SHA256(secret, timestamp + "." + body))`. The store refuses a timestamp older than 5 minutes |
| Success | Any `2xx` within 10 seconds |
| Retry | After 1 min, 5 min, 30 min, 2 h, 6 h, 24 h. Then the event is `FAILED` and waits for a person (`redeliver`) |
| Order | Not promised. `data.payment.version` lets the store drop an older event |
| Repeats | Possible. `id` lets the store drop a repeat |
| No URL configured | Events are kept and marked `SKIPPED`. Nothing is sent |
| Seen by finance | `GET /v1/events?status=&payment_id=` shows each event's status, attempts, next time and last error, but not its body. `POST /v1/events/{id}/redeliver` sends a delivered, failed or skipped event again, with the same id, from the first retry time on; a waiting one: `409 already_waiting` |

The event sender takes events with a lease of one minute, longer than a call to the store can take, so an event is not sent twice at once by two instances.

The sender takes events with `FOR UPDATE SKIP LOCKED` and never holds a transaction open while it calls the store.

## Tests

| Test | Proves |
|---|---|
| Contract tests per endpoint | Fields, statuses, error codes; the API description matches |
| Validation tests | Each rule of the field tables |
| Security tests | No key, wrong key, wrong scope; the return and webhook endpoints need no key and give nothing away; the two-second rule of the return page |
| Idempotency tests | Each row of the idempotency table |
| Event tests | The signature; retry times; a slow store; a repeat after a crash between "sent" and "marked sent" |
