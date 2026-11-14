package dev.mizan.app.application.model;

import dev.mizan.core.payment.Operation;
import dev.mizan.core.payment.OperationType;
import java.time.Instant;
import java.util.UUID;

/**
 * One thing Mizan asked the gateway to do to a payment, as it is stored.
 *
 * @param attempts calls sent to the gateway; written and committed before each call
 * @param resultCode why it failed; null otherwise
 * @param reason free text from the store, for a refund
 */
public record OperationRecord(
    UUID id,
    UUID paymentId,
    OperationType type,
    OperationStatus status,
    Origin origin,
    long amount,
    int attempts,
    Instant lastAttemptAt,
    String resultCode,
    String reason,
    Instant createdAt,
    Instant finishedAt) {

  /** A new operation whose first call is about to be sent. */
  public static OperationRecord inFlight(UUID id, UUID paymentId, OperationType type, Origin origin, long amount, String reason, Instant now) {
    return new OperationRecord(id, paymentId, type, OperationStatus.IN_FLIGHT, origin, amount, 1, now, null, reason, now, null);
  }

  /** An operation that needed no call, or that was done outside Mizan: it is recorded as already succeeded. */
  public static OperationRecord done(UUID id, UUID paymentId, OperationType type, Origin origin, long amount, String reason, Instant now) {
    return new OperationRecord(id, paymentId, type, OperationStatus.SUCCEEDED, origin, amount, 0, null, null, reason, now, now);
  }

  /** The part the rules read. Only for an operation in flight. */
  public Operation forRules() {
    return new Operation(type, amount, attempts, lastAttemptAt);
  }

  public OperationRecord finished(boolean succeeded, String code, Instant now) {
    return new OperationRecord(
        id, paymentId, type, succeeded ? OperationStatus.SUCCEEDED : OperationStatus.FAILED, origin, amount, attempts,
        lastAttemptAt, code, reason, createdAt, now);
  }
}
