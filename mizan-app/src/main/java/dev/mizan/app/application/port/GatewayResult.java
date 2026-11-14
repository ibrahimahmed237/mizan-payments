package dev.mizan.app.application.port;

import dev.mizan.core.sync.Snapshot;

/** What a gateway call gave. There are three results and no fourth. */
public sealed interface GatewayResult {

  /**
   * The gateway answered. The snapshot is its record of the payment now.
   *
   * @param gatewayPaymentId the payment's id at the gateway
   */
  record Done(Snapshot snapshot, String gatewayPaymentId) implements GatewayResult {}

  /** A clear no. Sending again will not help. */
  record Refused(String code, String message) implements GatewayResult {}

  /** No usable answer. The call may or may not have worked. */
  record Unknown(String reason) implements GatewayResult {}
}
