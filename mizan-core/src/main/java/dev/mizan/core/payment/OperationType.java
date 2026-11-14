package dev.mizan.core.payment;

/** One thing Mizan asks the gateway to do to a payment. */
public enum OperationType {
  CREATE,
  CAPTURE,
  CANCEL,
  REFUND
}
