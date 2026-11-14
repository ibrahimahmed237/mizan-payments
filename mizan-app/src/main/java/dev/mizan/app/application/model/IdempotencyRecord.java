package dev.mizan.app.application.model;

import java.util.UUID;

/**
 * What an {@code Idempotency-Key} made: a payment or an operation, never both.
 *
 * @param requestHash a hash of the request that first used the key
 */
public record IdempotencyRecord(String key, String requestHash, UUID paymentId, UUID operationId) {}
