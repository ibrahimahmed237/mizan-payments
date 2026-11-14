package dev.mizan.app.application.port;

import dev.mizan.core.payment.Method;
import java.util.UUID;

/**
 * One gateway. A call never throws for a gateway's fault and never sends twice: sending again is a decision of the
 * operation rules, not of an HTTP client.
 */
public interface GatewayClient {

  GatewayResult create(Create request);

  /** The payment's record now. A payment the gateway does not have is {@code Done} with a {@code NOT_FOUND} snapshot. */
  GatewayResult fetch(String gatewayPaymentId);

  GatewayResult capture(String gatewayPaymentId, long amount);

  GatewayResult cancel(String gatewayPaymentId);

  /** @param refundKey makes the same refund sent twice happen once, at gateways that support it */
  GatewayResult refund(String gatewayPaymentId, long amount, String refundKey);

  /**
   * A new payment.
   *
   * @param paymentId Mizan's id. A gateway that lets the caller choose the payment's id gets this one (rule R3)
   * @param token the card or wallet token; null for pay later. It lives only in memory during the call
   * @param callbackUrl where the gateway sends the customer when they are done: Mizan's return address
   */
  record Create(
      UUID paymentId,
      String reference,
      long amount,
      String currency,
      Method method,
      String token,
      boolean manualCapture,
      String callbackUrl,
      String description) {

    /** Without the token: this text may end in a log. */
    @Override
    public String toString() {
      return "Create[paymentId=" + paymentId + ", reference=" + reference + ", amount=" + amount + ", method=" + method + "]";
    }
  }
}
