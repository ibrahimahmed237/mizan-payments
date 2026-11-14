package dev.mizan.app.application.port;

import dev.mizan.app.application.model.DiscrepancyRecord;
import dev.mizan.core.payment.Gateway;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Differences a person must look at. */
public interface Discrepancies {

  /**
   * Opens a discrepancy about a payment, unless one of the same type is open already.
   *
   * @return the new discrepancy's id; empty when one was open already
   */
  Optional<UUID> open(String type, UUID paymentId, Long expected, Long actual, Instant at);

  /** Opens a discrepancy about a payment at a gateway that Mizan has no payment for, unless one is open already. */
  Optional<UUID> openForGatewayPayment(String type, Gateway gateway, String gatewayPaymentId, Instant at);

  /**
   * Opens a discrepancy about a settlement line, unless the line has one open already.
   *
   * @param paymentId the line's payment; null when Mizan has none
   * @param suspenseAmount the line's net amount held in suspense for it
   */
  Optional<UUID> openForLine(String type, UUID lineId, UUID paymentId, Long expected, Long actual, long suspenseAmount, Instant at);

  Optional<DiscrepancyRecord> find(UUID id);

  /** Locks it for the rest of the transaction. */
  Optional<DiscrepancyRecord> lock(UUID id);

  /** Open ones of a type that are about a payment and no line. */
  List<DiscrepancyRecord> openOfType(String type);

  /** Newest first. Each filter may be null. */
  List<DiscrepancyRecord> list(Boolean open, String type, int limit);

  void resolve(UUID id, String resolution, String note, Instant at);
}
