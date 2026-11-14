package dev.mizan.core.settlement;

import java.time.Instant;

/**
 * One line of a settlement file, in halalas.
 *
 * @param lineNumber its place in the file, from 1
 * @param transactionId the gateway's id of this line; the same transaction is never paid twice
 * @param gatewayPaymentId the payment at the gateway
 * @param typeText the type as written in the file
 * @param gross positive for a capture, negative for a refund
 * @param fee what the gateway kept; negative when it returns a fee
 * @param net what reached the bank: {@code gross − fee}
 */
public record SettlementLine(
    int lineNumber,
    String transactionId,
    String gatewayPaymentId,
    LineType type,
    String typeText,
    long gross,
    long fee,
    long net,
    Instant occurredAt) {}
