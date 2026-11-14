# Gateways and the simulator

## The interface

`mizan-app` talks to every gateway through one small interface. `mizan-core` does not know gateways exist.

| Call | Meaning |
|---|---|
| `create(request)` | Start a payment. For cards the request carries the id Mizan chose |
| `fetch(gatewayPaymentId)` | The payment's record now |
| `capture(gatewayPaymentId, amount)` | Take held money |
| `cancel(gatewayPaymentId)` | Void a hold |
| `refund(gatewayPaymentId, amount, refundKey)` | Give money back. `refundKey` is used only by gateways that support it |

Every call returns one of three results. There is no fourth.

| Result | Meaning | Example |
|---|---|---|
| `Done(snapshot)` | The gateway answered. The snapshot is its record | 200, 201 |
| `Refused(code, message)` | A clear no. Sending again will not help | Card declined, bad token, 4xx |
| `Unknown(reason)` | No usable answer. The call may or may not have worked | Timeout, connection error, 5xx, a body that cannot be read |

A response Mizan cannot read is `Unknown`, never `Done` or `Refused`: guessing is how money is lost.

| What a gateway can do | Card gateway | Pay later |
|---|---|---|
| Caller chooses the payment id | Yes | No |
| Captures by itself when asked | Yes | No: Mizan sends the capture |
| Partial capture | Yes | No (in Mizan) |
| Refund key: the same refund sent twice is done once | No | Yes |

## Normalizing

Each adapter turns the gateway's words into the [snapshot](sync.md#the-snapshot).

| Card gateway says | Snapshot |
|---|---|
| `initiated` | `INITIATED`, with the bank page's URL |
| `authorized` | `AUTHORIZED` |
| `paid` | `PAID`, `captured = amount` |
| `captured` | `PAID`, `captured` as given |
| `refunded` | `PAID`, with `refunded` as given |
| `voided`, nothing captured | `VOIDED` |
| `voided`, after capture | `PAID`, `refunded = captured` |
| `failed` | `FAILED`, with the message |
| 404 | `NOT_FOUND` |

| Pay-later provider says | Snapshot |
|---|---|
| `CREATED` | `INITIATED`, with the checkout URL |
| `AUTHORIZED` | `AUTHORIZED` |
| `CLOSED`, something captured | `PAID` |
| `CLOSED`, nothing captured | `VOIDED` |
| `REJECTED` | `FAILED` |
| `EXPIRED` | `EXPIRED` |

An adapter checks `0 ≤ refunded ≤ captured ≤ amount`. A record that breaks it is `Unknown`.

Every call has a connect timeout of 2 seconds and a total timeout of 10 seconds. The adapters do not retry: sending again is a decision of the [operation rules](payments.md#when-the-result-is-not-known), not of an HTTP client.

If the gateway answers a create with "this id exists", the adapter fetches that payment and returns its record.

## Webhooks from gateways

| Gateway | How the webhook is checked |
|---|---|
| Card gateway | A shared secret inside the body, compared in constant time. This is how the real card gateway that the simulator follows does it |
| Pay later | A signature header: `HMAC-SHA256(secret, body)` |

From a webhook Mizan takes two things only: the event's id and the payment's id. It does not read the status from it (rule R2).

A pay-later session carries Mizan's payment id as the provider's order reference. So a pay-later webhook can be tied to its payment by either id.

## The simulator

`mizan-simulator` is one small web application that plays both gateways. It follows the public documents of a real Saudi card gateway and a real pay-later provider: the same states, the same operations, the same webhook events, the same retry times for webhooks. Where the documents say nothing, it is strict.

It keeps its **own book**: for every payment, what was really held, taken and given back. The proof run compares Mizan with this book.

| Card gateway | |
|---|---|
| `POST /card/v1/tokens` | Makes a token from a test card. Stands in for the gateway's page |
| `POST /card/v1/payments` | Creates a payment with the id the caller gives. The same id again returns the same payment and charges nothing |
| `GET /card/v1/payments/{id}` | The record |
| `POST /card/v1/payments/{id}/capture`, `/void`, `/refund` | The operations |
| `GET /card/3ds/{id}` and `POST /card/3ds/{id}` | The bank's page: the customer approves or refuses |
| `GET /card/v1/settlements/{date}` | The settlement file of a day |

| Pay-later provider | |
|---|---|
| `POST /paylater/v2/checkout` | A session and its page URL |
| `GET /paylater/checkout/{id}` and `POST` | The provider's page: approve, refuse, leave |
| `GET /paylater/v2/payments/{id}` | The record |
| `POST /paylater/v2/payments/{id}/captures`, `/close`, `/refunds` | The operations |
| `GET /paylater/v2/settlements/{date}` | The settlement file of a day |

Test cards decide the normal result, as in real test modes:

| Card ends in | Result |
|---|---|
| `…1111` | Paid without a bank code |
| `…3333` | Needs the bank's code |
| `…0002` | Declined: insufficient funds |
| `…0069` | Declined: expired card |

## Faults

A fault is asked for per payment, in the payment's metadata (`sim_fault`), or for everything through the control API. The proof run plans faults from a seed, so a run can be repeated exactly.

| Fault | What the simulator does | Rule it attacks |
|---|---|---|
| `lost_answer` | Does the work, then drops the connection | R3: charged, and Mizan did not hear |
| `timeout_before` | Waits past the timeout and does nothing | Becomes `FAILED` after the quiet minute, not before; nothing is sent twice |
| `slow_apply` | Answers with a timeout, does the work a few seconds later | The quiet minute: a slow success is not called a failure |
| `very_slow_apply` | Does the work after the quiet minute | A refund reported as failed is still found and counted; a payment becomes a late capture |
| `error_500` | Answers 500 without doing the work | Unknown is not a refusal |
| `error_500_after` | Does the work, answers 500 | Unknown is not a refusal, and not a failure |
| `garbage` | Answers 200 with a body that is not the record | An unreadable answer is `Unknown` |
| `fetch_down` | `fetch` fails for a while | The worker keeps trying |
| `webhook_drop` | Sends no webhook | R2: a webhook is not needed |
| `webhook_dup` | Sends each webhook three times | The inbox |
| `webhook_late` | Sends the webhook minutes later | Late hints are harmless |
| `webhook_reorder` | Sends `paid` before `authorized`, `refunded` before `paid` | Order does not matter |
| `webhook_forged` | Sends a webhook with a wrong secret, and one that says "paid" for an unpaid payment | A webhook cannot move money |
| `webhook_early` | Sends the webhook before answering the call | The payment row exists before the call |
| `customer_late` | The customer approves after Mizan has expired the payment | Late capture |
| `customer_leaves` | The customer never comes back | Expiry |
| `two_tabs` | The customer pays two payments of one order | One order, one payment that counts |
| `amount_changed` | The record shows another amount | `AMOUNT_CONFLICT` |
| `external_refund` | A refund is made directly in the simulator, not through Mizan | External refunds |
| `hold_released` | The record stays `authorized` although the hold is gone; a capture is refused | Assumption A4 |

Settlement faults are in [reconciliation](reconciliation.md#settlement-faults).

The control API (`/_sim/...`) sets faults, moves the simulator's clock, makes a customer act, and returns the book. It has no authentication: the simulator is a test tool and must never be on a public address.

## The real gateway

A third adapter talks to a real card gateway's test mode. It is built from the gateway's public documents. Its tests are skipped without test keys, and say so. With keys, one command runs the main flows against the real test mode and writes a report in `docs/conformance/`. Keys come from the environment only.

## Tests

| Test | Proves |
|---|---|
| Adapter tests against the simulator | Each call; each result kind; normalizing, row by row |
| Adapter tests with a stub server | Timeouts, connection drops, 5xx, unreadable bodies are `Unknown`; 4xx is `Refused`; what is sent (paths, headers, bodies) |
| Simulator tests | Its states, its own book, the same id twice, each fault |
| Webhook check tests | Right secret, wrong secret, missing secret, changed body |
