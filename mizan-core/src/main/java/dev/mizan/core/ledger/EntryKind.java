package dev.mizan.core.ledger;

/** What money fact an entry records. */
public enum EntryKind {
  CAPTURE,
  REFUND,
  SETTLE,
  SUSPENSE,
  REMATCH,
  ACCEPT
}
