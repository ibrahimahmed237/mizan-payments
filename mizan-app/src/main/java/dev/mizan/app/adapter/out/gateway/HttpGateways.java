package dev.mizan.app.adapter.out.gateway;

import dev.mizan.app.application.port.GatewayClient;
import dev.mizan.app.application.port.Gateways;
import dev.mizan.core.payment.Gateway;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/** The two gateway clients, each with its own address and key. */
public final class HttpGateways implements Gateways {

  /** Where a gateway is and how Mizan proves who it is. */
  public record Access(String url, String key) {}

  private final GatewayClient card;
  private final GatewayClient payLater;

  public HttpGateways(Access cardGateway, Access payLaterProvider, Duration connectTimeout, Duration timeout) {
    // The card gateway takes the key as the user name of HTTP Basic, with no password. The provider takes a bearer key.
    String basic = "Basic " + Base64.getEncoder().encodeToString((cardGateway.key() + ":").getBytes(StandardCharsets.UTF_8));
    this.card = new CardGatewayClient(new GatewayHttp("card", cardGateway.url(), basic, connectTimeout, timeout));
    this.payLater = new PayLaterClient(
        new GatewayHttp("pay_later", payLaterProvider.url(), "Bearer " + payLaterProvider.key(), connectTimeout, timeout));
  }

  @Override
  public GatewayClient of(Gateway gateway) {
    return switch (gateway) {
      case CARD_GATEWAY -> card;
      case PAY_LATER -> payLater;
    };
  }
}
