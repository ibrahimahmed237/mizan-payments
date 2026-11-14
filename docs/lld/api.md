# API

JSON over HTTPS. All paths start with `/v1`. The machine-readable description is served at `/v3/api-docs`.

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
| `GET /v1/payments?reference=&status=&cursor=&limit=` | payments | Lists payments, newest first |
| `POST /v1/payments/{id}/capture` | payments | Captures a hold |
| `POST /v1/payments/{id}/cancel` | payments | Cancels |
| `POST /v1/payments/{id}/refunds` | payments | Refunds |
| `GET /v1/payments/{id}/refunds` | payments | Lists a payment's refunds |
| `POST /v1/payments/{id}/sync` | payments | Asks for a sync now. Returns the payment |
| `POST /v1/webhooks/{gateway}` | — (gateway secret) | A webhook from a gateway |
| `GET /v1/return/{id}` | — | The customer comes back |
| `POST /v1/settlements` | backoffice | Uploads a settlement file |
| `GET /v1/settlements`, `GET /v1/settlements/{id}` | backoffice | Files and their progress |
| `GET /v1/discrepancies?status=&type=` | backoffice | Lists discrepancies |
| `POST /v1/discrepancies/{id}/retry`, `/accept` | backoffice | Closes a discrepancy |
| `GET /v1/ledger/balances`, `GET /v1/ledger/entries` | backoffice | The books |
| `GET /v1/reports/reconciliation` | backoffice | Unsettled money, suspense, open discrepancies |
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

| `status` | What the store does |
|---|---|
| `PENDING` with `next_action` | Send the customer to the URL |
| `PENDING` with `pending_reason: gateway_unknown` | Wait for the event, or read the payment again. The answer comes within about a minute. Until then a new payment for the same `reference` is refused |
| `AUTHORIZED` | Capture when the order ships |
| `CAPTURED` | Deliver |
| `FAILED` | Show `failure.message`. A new payment needs a new `Idempotency-Key` |

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

- "The same body" is compared by a hash of the method, the path and the JSON, with object keys in order.
- A key is a string of 1 to 128 printable characters. Keys are kept for 30 days.
- No answer is stored, only the id. So a crash between "made" and "answered" loses nothing: the repeat finds the payment and shows its real state.

## Errors

`application/problem+json`:

```json
{ "type": "https://mizan.example/problems/refund_too_large",
  "title": "The refund is larger than what can still be refunded.",
  "status": 422, "code": "refund_too_large",
  "detail": "Refundable: 10000 halalas.", "request_id": "..." }
```

| Status | Used for |
|---|---|
| `400` | A body that cannot be read, a missing header |
| `401`, `403` | No key, wrong key, missing scope |
| `404` | No such payment, discrepancy, file |
| `409` | The payment's state does not allow it; an operation is in progress; the order has an unresolved or paid payment |
| `413` | A body that is too large |
| `422` | A field breaks a rule |
| `429` | Too many requests. `Retry-After` says when to come back |
| `500`, `503` | Mizan's own fault. Safe to send again with the same key |

A gateway that is down is **not** a `5xx`: the payment or operation is answered as `PENDING` (see the [design](../DESIGN.md#11-api-conventions)).

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

The sender takes events with `FOR UPDATE SKIP LOCKED` and never holds a transaction open while it calls the store.

## Tests

| Test | Proves |
|---|---|
| Contract tests per endpoint | Fields, statuses, error codes; the API description matches |
| Validation tests | Each rule of the field tables |
| Security tests | No key, wrong key, wrong scope; the return and webhook endpoints need no key and give nothing away; the two-second rule of the return page |
| Idempotency tests | Each row of the idempotency table |
| Event tests | The signature; retry times; a slow store; a repeat after a crash between "sent" and "marked sent" |
