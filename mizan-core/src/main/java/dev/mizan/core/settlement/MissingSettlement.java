package dev.mizan.core.settlement;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;

/**
 * The daily check: money that Mizan recorded long ago and that no settlement file has paid out (or taken back) yet.
 * See docs/lld/reconciliation.md, "Step 3".
 */
public final class MissingSettlement {

  private MissingSettlement() {}

  /**
   * @param capturedAt when money was first captured; null when never
   * @param lastRefundAt when money was last refunded; null when never
   * @param after how long a gateway may take to pay out
   * @return the amount missing from settlement, captures and refunds together; empty when nothing is missing yet
   */
  public static OptionalLong missing(PaymentView payment, Instant capturedAt, Instant lastRefundAt, Duration after, Instant now) {
    long amount = 0;
    if (payment.unsettledCaptured() > 0 && old(capturedAt, after, now)) {
      amount += payment.unsettledCaptured();
    }
    if (payment.unsettledRefunded() > 0 && old(lastRefundAt, after, now)) {
      amount += payment.unsettledRefunded();
    }
    return amount == 0 ? OptionalLong.empty() : OptionalLong.of(amount);
  }

  private static boolean old(Instant at, Duration after, Instant now) {
    return at != null && !now.isBefore(at.plus(after));
  }
}
