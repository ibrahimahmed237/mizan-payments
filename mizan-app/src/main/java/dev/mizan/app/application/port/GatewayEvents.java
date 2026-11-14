package dev.mizan.app.application.port;

import dev.mizan.core.payment.Gateway;
import java.time.Instant;
import java.util.UUID;

/** The webhooks that came in. Only what is needed to skip a repeat is kept, never the body. */
public interface GatewayEvents {

  /**
   * @param paymentId Mizan's payment that the event is about; null when none matched
   * @return false when this event of this gateway was saved before; nothing is saved then
   */
  boolean save(Gateway gateway, String eventId, String type, String gatewayPaymentId, UUID paymentId, Instant now);
}
