package dev.mizan.core.sync;

/** What to do with money that was taken after the store was told the payment was dead. */
public enum LateCapturePolicy {
  /** Give it back: the store did not deliver the order. */
  REFUND,
  /** Keep it: the store would rather deliver. */
  KEEP
}
