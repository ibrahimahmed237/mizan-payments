package dev.mizan.core.payment;

import java.time.Instant;
import java.util.Objects;

/**
 * An operation in flight: sent to the gateway, result not known yet.
 *
 * @param amount for a capture and a refund; zero otherwise
 * @param attempts calls sent so far; written before each call
 * @param lastAttemptAt when the last call was sent
 */
public record Operation(OperationType type, long amount, int attempts, Instant lastAttemptAt) {

  public Operation {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(lastAttemptAt, "lastAttemptAt");
    if (amount < 0 || attempts < 1) {
      throw new IllegalArgumentException("An operation has an amount of zero or more and at least one attempt.");
    }
  }
}
