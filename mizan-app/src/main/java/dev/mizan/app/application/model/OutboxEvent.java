package dev.mizan.app.application.model;

import java.time.Instant;
import java.util.UUID;

/**
 * An event for the store, as it is stored.
 *
 * @param payload the JSON body, exactly as it is sent
 * @param attempts how many times it was sent so far
 */
public record OutboxEvent(
    UUID id,
    String type,
    UUID paymentId,
    String payload,
    String status,
    int attempts,
    Instant nextAttemptAt,
    String lastError,
    Instant createdAt,
    Instant deliveredAt) {}
