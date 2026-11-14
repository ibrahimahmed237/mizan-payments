package dev.mizan.simulator;

/** A way the simulator breaks its next settlement file. See docs/lld/reconciliation.md. */
public enum FileFault {
  /** Leaves the payment's capture out, for ever. */
  MISSING_LINE,
  /** Adds a capture of 50.00 for a payment that does not exist. */
  EXTRA_LINE,
  /** The payment's capture line has 1.00 more than was captured. */
  WRONG_AMOUNT,
  /** The payment's capture line has 1.00 less than was captured. */
  SHORT_AMOUNT,
  /** The payment's capture line has a fee 7 halalas higher than the fee rule. */
  WRONG_FEE,
  /** Repeats the payment's capture line from an earlier payout, with the same transaction id. */
  DUPLICATE_LINE,
  /** Adds a chargeback line for the payment. */
  CHARGEBACK_LINE,
  /** The header's total is one halala too high. */
  BAD_TOTAL,
  /** The file is cut off in the middle of its last line. */
  BROKEN_FILE
}
