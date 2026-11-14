package dev.mizan.app.application.model;

import dev.mizan.core.payment.Gateway;
import dev.mizan.core.settlement.SettlementLine;
import java.util.UUID;

/**
 * A line of a settlement file, as it is stored.
 *
 * @param paymentId Mizan's payment of the line, once matching found it; null otherwise
 * @param synced its payment was synced after the line first did not fit
 */
public record LineRecord(UUID id, UUID fileId, Gateway gateway, SettlementLine line, LineStatus status, UUID paymentId, boolean synced) {}
