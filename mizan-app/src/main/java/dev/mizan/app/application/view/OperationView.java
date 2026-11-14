package dev.mizan.app.application.view;

import dev.mizan.app.application.model.OperationRecord;
import dev.mizan.app.application.model.OperationStatus;
import dev.mizan.app.application.view.PaymentView.Failure;
import java.time.Instant;
import java.util.UUID;

/** A capture, cancel or refund as the API returns it. */
public record OperationView(
    UUID id,
    UUID paymentId,
    String type,
    String status,
    long amount,
    String reason,
    String origin,
    Failure failure,
    Instant createdAt,
    Instant finishedAt) {

  public static OperationView of(OperationRecord operation) {
    return new OperationView(
        operation.id(),
        operation.paymentId(),
        operation.type().name(),
        operation.status() == OperationStatus.IN_FLIGHT ? "PENDING" : operation.status().name(),
        operation.amount(),
        operation.reason(),
        operation.origin().name(),
        operation.resultCode() == null ? null : new Failure(operation.resultCode(), null),
        operation.createdAt(),
        operation.finishedAt());
  }
}
