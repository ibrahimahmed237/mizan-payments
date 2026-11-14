package dev.mizan.app.application.model;

/** Where a settlement line stands. */
public enum LineStatus {
  /** Not matched yet. */
  NEW,
  /** It did not fit; its payment must be synced before it is tried once more. */
  NEEDS_SYNC,
  /** It fits what Mizan recorded, and is settled. */
  MATCHED,
  /** It does not fit. Its net amount is in suspense, and a discrepancy is open. */
  SUSPENSE,
  /** It did not fit, and finance accepted the difference. */
  ACCEPTED;

  public boolean waiting() {
    return this == NEW || this == NEEDS_SYNC;
  }
}
