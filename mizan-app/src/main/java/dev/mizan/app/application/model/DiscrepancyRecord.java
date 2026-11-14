package dev.mizan.app.application.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A difference a person must look at.
 *
 * @param paymentId the payment it is about; null when Mizan has none
 * @param lineId the settlement line it is about; null when it is about no line
 * @param suspenseAmount the line's net amount held in suspense for it; zero when it holds none
 * @param seq the order in which discrepancies were opened; a list's pages go by it
 */
public record DiscrepancyRecord(
    UUID id,
    String type,
    boolean open,
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
    Instant resolvedAt,
    long seq) {}
