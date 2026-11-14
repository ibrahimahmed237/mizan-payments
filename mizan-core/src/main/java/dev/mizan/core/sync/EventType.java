package dev.mizan.core.sync;

import dev.mizan.core.payment.OperationType;

/** What the store is told. */
public enum EventType {
  PAYMENT_AUTHORIZED("payment.authorized"),
  PAYMENT_CAPTURED("payment.captured"),
  PAYMENT_FAILED("payment.failed"),
  PAYMENT_CANCELED("payment.canceled"),
  PAYMENT_EXPIRED("payment.expired"),
  REFUND_SUCCEEDED("refund.succeeded"),
  REFUND_FAILED("refund.failed"),
  CAPTURE_FAILED("capture.failed"),
  CANCEL_FAILED("cancel.failed"),
  DISCREPANCY_OPENED("discrepancy.opened");

  private final String wireName;

  EventType(String wireName) {
    this.wireName = wireName;
  }

  /** The name in the event sent to the store. */
  public String wireName() {
    return wireName;
  }

  /**
   * The event for an operation that ended, or null when the payment's own event already says it: a capture that
   * worked is {@code payment.captured}, a cancel that worked is {@code payment.canceled}, a create has the
   * payment's events.
   */
  public static EventType forOperation(OperationType type, boolean succeeded) {
    return switch (type) {
      case REFUND -> succeeded ? REFUND_SUCCEEDED : REFUND_FAILED;
      case CAPTURE -> succeeded ? null : CAPTURE_FAILED;
      case CANCEL -> succeeded ? null : CANCEL_FAILED;
      case CREATE -> null;
    };
  }
}
