package dev.mizan.core.settlement;

/** Something that does not match. See docs/lld/reconciliation.md. */
public enum DiscrepancyType {
  /** The line names a payment Mizan does not have. */
  UNKNOWN_PAYMENT(true),
  /** The gateway paid out a payment Mizan does not hold as captured, even after a sync. */
  NOT_CAPTURED_HERE(true),
  /** The line's amount is more than what is still unsettled for the payment. */
  AMOUNT_MISMATCH(true),
  /** A refund line with no refund in Mizan, even after a sync. */
  UNKNOWN_REFUND(true),
  /** The same gateway transaction was settled before. */
  DUPLICATE_SETTLEMENT(true),
  /** A line that is not a capture or a refund. */
  UNKNOWN_LINE_TYPE(true),
  /** The fee is not what the fee table says. The line is posted with the real fee. */
  FEE_MISMATCH(false),
  /** Captured or refunded long ago and in no file. */
  MISSING_FROM_SETTLEMENT(false),
  /** The gateway's record has another amount than the payment (from sync). */
  AMOUNT_CONFLICT(false),
  /** A webhook for a payment Mizan does not have. */
  UNKNOWN_PAYMENT_EVENT(false);

  private final boolean holdsMoneyInSuspense;

  DiscrepancyType(boolean holdsMoneyInSuspense) {
    this.holdsMoneyInSuspense = holdsMoneyInSuspense;
  }

  /** The line's money is in the suspense account until the discrepancy is closed. */
  public boolean holdsMoneyInSuspense() {
    return holdsMoneyInSuspense;
  }
}
