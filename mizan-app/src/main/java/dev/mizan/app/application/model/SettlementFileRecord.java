package dev.mizan.app.application.model;

import dev.mizan.core.payment.Gateway;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An imported settlement file: one payout of one gateway.
 *
 * @param sha256 the hash of the file as it was uploaded
 */
public record SettlementFileRecord(
    UUID id, Gateway gateway, String payoutId, LocalDate payoutDate, long totalNet, int lineCount, String sha256, Instant createdAt) {}
