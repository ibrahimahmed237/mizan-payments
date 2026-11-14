package dev.mizan.simulator;

/** A way the simulator fails on purpose. See docs/lld/gateways.md. */
public enum Fault {
  /** Does the work, then drops the connection. */
  LOST_ANSWER,
  /** Waits past the caller's timeout and does nothing. */
  TIMEOUT_BEFORE,
  /** Lets the caller time out, then does the work a few seconds later. */
  SLOW_APPLY,
  /** Lets the caller time out, then does the work two minutes later: after Mizan's quiet minute. */
  VERY_SLOW_APPLY,
  /** Answers 500 without doing the work. */
  ERROR_500,
  /** Does the work, then answers 500. */
  ERROR_500_AFTER,
  /** Does the work, then answers 200 with a body that is not the record. */
  GARBAGE,

  /** Sticky, per payment: sends no webhook. */
  WEBHOOK_DROP,
  /** Sticky: sends every webhook three times. */
  WEBHOOK_DUP,
  /** Sticky: sends every webhook five minutes late. */
  WEBHOOK_LATE,
  /** Sticky: later events arrive before earlier ones. */
  WEBHOOK_REORDER,
  /** Sticky: also sends a webhook with a wrong secret, and one with the right secret that claims "paid". */
  WEBHOOK_FORGED,
  /** Sticky: sends the webhook before the call is answered. */
  WEBHOOK_EARLY,
  /** Sticky: a fetch shows another amount. */
  AMOUNT_CHANGED,
  /** Sticky: the record stays authorized, but a capture is refused: the bank released the hold. */
  HOLD_RELEASED;

  /** A fault that stays on a payment, as opposed to one that hits one call. */
  public boolean sticky() {
    return ordinal() >= WEBHOOK_DROP.ordinal();
  }
}
