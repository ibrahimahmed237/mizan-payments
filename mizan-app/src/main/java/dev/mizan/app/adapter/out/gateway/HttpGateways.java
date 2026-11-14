package dev.mizan.app.adapter.out.gateway;

import dev.mizan.app.application.port.GatewayClient;
import dev.mizan.app.application.port.Gateways;
import dev.mizan.core.payment.Gateway;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
    this(cardGateway, payLaterProvider, connectTimeout, timeout, new SimpleMeterRegistry());
  }

  /** @param meters where each call is counted and timed */
  public HttpGateways(Access cardGateway, Access payLaterProvider, Duration connectTimeout, Duration timeout, MeterRegistry meters) {
    // The card gateway takes the key as the user name of HTTP Basic, with no password. The provider takes a bearer key.
    String basic = "Basic " + Base64.getEncoder().encodeToString((cardGateway.key() + ":").getBytes(StandardCharsets.UTF_8));
    this.card = new MeteredGatewayClient(
        new CardGatewayClient(new GatewayHttp("card", cardGateway.url(), basic, connectTimeout, timeout)), "card", meters);
    this.payLater = new MeteredGatewayClient(
        new PayLaterClient(new GatewayHttp("pay_later", payLaterProvider.url(), "Bearer " + payLaterProvider.key(), connectTimeout, timeout)),
        "pay_later", meters);
  }

  @Override
  public GatewayClient of(Gateway gateway) {
    return switch (gateway) {
      case CARD_GATEWAY -> card;
      case PAY_LATER -> payLater;
    };
  }
}
