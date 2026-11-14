package dev.mizan.core.payment;

/** An operation Mizan starts by itself. A payment gets each kind at most once. */
public enum FollowUp {
  /** Capture a pay-later payment that the store wanted captured at once. */
  AUTO_CAPTURE(OperationType.CAPTURE),
  /** Release a hold that appeared on a dead payment, or whose time ran out. */
  AUTO_VOID(OperationType.CANCEL),
  /** Return a late capture. */
  AUTO_REFUND(OperationType.REFUND);

  private final OperationType operation;

  FollowUp(OperationType operation) {
    this.operation = operation;
  }

  public OperationType operation() {
    return operation;
  }
}
