package dev.mizan.app.application.view;

import dev.mizan.app.application.model.DiscrepancyRecord;
import java.time.Instant;
import java.util.UUID;

/** A discrepancy as the API returns it. */
public record DiscrepancyView(
    UUID id,
    String type,
    String status,
    UUID paymentId,
    UUID lineId,
    String gateway,
    String gatewayPaymentId,
    Long expected,
    Long actual,
    long suspenseAmount,
    String resolution,
    String note,
    Instant createdAt,
    Instant resolvedAt) {

  public static DiscrepancyView of(DiscrepancyRecord d) {
    return new DiscrepancyView(
        d.id(), d.type(), d.open() ? "OPEN" : "RESOLVED", d.paymentId(), d.lineId(), d.gateway(), d.gatewayPaymentId(), d.expected(),
        d.actual(), d.suspenseAmount(), d.resolution(), d.note(), d.createdAt(), d.resolvedAt());
  }
}
