package dev.mizan.app.application.port;

import dev.mizan.core.payment.Gateway;

/** The gateway clients, by gateway. */
public interface Gateways {

  GatewayClient of(Gateway gateway);
}
