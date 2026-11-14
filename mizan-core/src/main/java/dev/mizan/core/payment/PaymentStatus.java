package dev.mizan.core.payment;

/** Where a payment stands, as the store sees it. */
public enum PaymentStatus {
  /** Created. The result is not known yet, or the customer must act. */
  PENDING,
  /** The bank holds the amount for the store. */
  AUTHORIZED,
  /** Money was taken. Refunds are counted in the refunded total; the status stays. */
  CAPTURED,
  /** The gateway refused. */
  FAILED,
  /** The store cancelled, or the hold was voided. */
  CANCELED,
  /** The customer did not finish in time, or the hold ran out. */
  EXPIRED;

  /** Final for the store. Not final for the truth: money taken later is a late capture. */
  public boolean isDead() {
    return this == FAILED || this == CANCELED || this == EXPIRED;
  }
}
