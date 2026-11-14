package dev.mizan.core.payment;

/** How the customer pays. */
public enum Method {
  CARD(Gateway.CARD_GATEWAY),
  APPLE_PAY(Gateway.CARD_GATEWAY),
  PAY_LATER(Gateway.PAY_LATER);

  private final Gateway gateway;

  Method(Gateway gateway) {
    this.gateway = gateway;
  }

  public Gateway gateway() {
    return gateway;
  }
}
