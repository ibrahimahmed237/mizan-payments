package dev.mizan.core.settlement;

import dev.mizan.core.payment.Gateway;
import java.time.LocalDate;

/**
 * The first record of a settlement file: one payout.
 *
 * @param totalNet the amount sent to the bank, in halalas
 */
public record SettlementHeader(String payoutId, Gateway gateway, String currency, LocalDate payoutDate, long totalNet, int lineCount) {}
