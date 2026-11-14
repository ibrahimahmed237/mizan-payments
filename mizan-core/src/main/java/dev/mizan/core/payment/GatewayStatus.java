package dev.mizan.core.payment;

/**
 * A payment's status at the gateway, in one vocabulary for every gateway. A status has a step: a payment only moves
 * to a higher step, so a record with a lower step than the last one seen is an old record.
 */
public enum GatewayStatus {
  NOT_FOUND(0),
  INITIATED(1),
  AUTHORIZED(2),
  FAILED(3),
  VOIDED(3),
  EXPIRED(3),
  PAID(4);

  private final int step;

  GatewayStatus(int step) {
    this.step = step;
  }

  public int step() {
    return step;
  }
}
