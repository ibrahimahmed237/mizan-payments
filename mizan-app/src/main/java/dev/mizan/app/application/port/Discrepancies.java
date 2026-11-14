package dev.mizan.app.application.port;

import dev.mizan.core.payment.Gateway;
import java.time.Instant;
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
}
