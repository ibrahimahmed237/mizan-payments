# ADR-0010: A simulator with its own book, and a proof run

- **Status:** Accepted
- **Date:** 2026-11-14

## Context

"Never charged twice, no payment lost" cannot be shown against a real gateway: its test mode does not lose answers or drop webhooks when asked.

## Decision

`mizan-simulator` plays both gateways. It can fail on purpose: lose an answer after doing the work, apply a call late, send webhooks twice or never, break its settlement file. It keeps **its own book** of what really happened to each payment.

The **proof run** makes thousands of payments with faults chosen from a seed, lets Mizan settle, and compares Mizan with the simulator's book.

## Consequences

- **Good:** the main claims of the design are checked by a program, with a number at the end.
- **Good:** a failed run can be repeated exactly from its seed.
- **Cost:** the simulator is not the gateway. It follows the gateways' public documents; the assumptions are listed in the design; a real test mode run is a separate layer.
- **Cost:** the simulator is code that must be tested too.

## Alternatives

- **Mocks in unit tests only.** They test what the author thought of, in the order the author thought of it.
- **Only a real test mode.** Cannot make the failures that matter.
