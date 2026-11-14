package dev.mizan.app.application.view;

import dev.mizan.app.application.model.OutboxEvent;
import java.time.Instant;
import java.util.UUID;

/** An event for the store, as finance sees it: what it is and how its sending goes. The body is not shown. */
public record EventView(
    UUID id, String type, UUID paymentId, String status, int attempts, Instant nextAttemptAt, String lastError, Instant createdAt,
    Instant deliveredAt) {

  public static EventView of(OutboxEvent event) {
    return new EventView(
        event.id(), event.type(), event.paymentId(), event.status(), event.attempts(), event.nextAttemptAt(), event.lastError(),
        event.createdAt(), event.deliveredAt());
  }
}
