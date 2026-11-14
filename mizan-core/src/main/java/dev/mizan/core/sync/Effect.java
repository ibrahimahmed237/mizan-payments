package dev.mizan.core.sync;

import dev.mizan.core.payment.FollowUp;

/** One thing the caller must write down after a decision. */
public sealed interface Effect {

  /** Money was taken: a ledger entry. */
  record Captured(long amount) implements Effect {}

  /** Money was given back: a ledger entry. */
  record Refunded(long amount) implements Effect {}

  /** The operation in flight ended. {@code code} says why it failed; null when it succeeded. */
  record OperationFinished(boolean succeeded, String code) implements Effect {}

  /** A refund that Mizan did not start, for example one made in the gateway's dashboard. */
  record ExternalRefund(long amount) implements Effect {}

  /** The order's other unfinished payments must be canceled: this one holds or has the money. */
  record CancelSiblings() implements Effect {}

  /** Mizan must start this operation by itself when no operation is in flight. */
  record FollowUpRequested(FollowUp kind) implements Effect {}

  /** The store must be told. */
  record Notify(EventType event) implements Effect {}

  /** A person must look at this. */
  record Anomaly(AnomalyType type) implements Effect {}
}
