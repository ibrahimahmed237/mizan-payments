package dev.mizan.core.sync;

import dev.mizan.core.payment.GatewayStatus;
import java.util.Objects;

/**
 * What the gateway says about one payment at one moment, in one shape for every gateway. An adapter builds it and
 * this constructor refuses a record that cannot be true: {@code 0 ≤ refunded ≤ captured ≤ amount}, and something
 * captured exactly when the status is {@code PAID}.
 */
public record Snapshot(
    GatewayStatus status,
    long amount,
    String currency,
    long captured,
    long refunded,
    String actionUrl,
    String failureCode,
    String failureMessage,
    String scheme,
    String last4) {

  public Snapshot {
    Objects.requireNonNull(status, "status");
    if (status != GatewayStatus.NOT_FOUND) {
      Objects.requireNonNull(currency, "currency");
      if (amount <= 0 || refunded < 0 || refunded > captured || captured > amount) {
        throw new IllegalArgumentException(
            "The limits 0 ≤ refunded ≤ captured ≤ amount are broken: " + refunded + ", " + captured + ", " + amount);
      }
      if ((status == GatewayStatus.PAID) != (captured > 0)) {
        throw new IllegalArgumentException("A record is PAID exactly when something was captured.");
      }
    }
  }

  /** The gateway has no such payment. */
  public static Snapshot notFound() {
    return new Snapshot(GatewayStatus.NOT_FOUND, 0, null, 0, 0, null, null, null, null, null);
  }

  /** A record with no money taken. */
  public static Snapshot of(GatewayStatus status, long amount, String currency) {
    return new Snapshot(status, amount, currency, 0, 0, null, null, null, null, null);
  }

  /** A record with money taken. */
  public static Snapshot paid(long amount, String currency, long captured, long refunded) {
    return new Snapshot(GatewayStatus.PAID, amount, currency, captured, refunded, null, null, null, null, null);
  }

  public Snapshot withActionUrl(String url) {
    return new Snapshot(status, amount, currency, captured, refunded, url, failureCode, failureMessage, scheme, last4);
  }

  public Snapshot withFailure(String code, String message) {
    return new Snapshot(status, amount, currency, captured, refunded, actionUrl, code, message, scheme, last4);
  }

  public Snapshot withCard(String cardScheme, String cardLast4) {
    return new Snapshot(
        status, amount, currency, captured, refunded, actionUrl, failureCode, failureMessage, cardScheme, cardLast4);
  }
}
