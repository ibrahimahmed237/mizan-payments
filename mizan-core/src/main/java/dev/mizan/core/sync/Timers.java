package dev.mizan.core.sync;

import dev.mizan.core.payment.FollowUp;
import dev.mizan.core.payment.GatewayStatus;
import dev.mizan.core.payment.Payment;
import dev.mizan.core.payment.PaymentStatus;
import dev.mizan.core.sync.Effect.FollowUpRequested;
import dev.mizan.core.sync.Effect.Notify;
import java.time.Instant;
import java.util.List;

/**
 * Ends a payment that waited too long. Each function is meant to run right after a sync, so it decides on fresh
 * facts, and never while an operation is in flight.
 */
public final class Timers {

  private Timers() {}

  /** A pending payment whose customer never finished. */
  public static Decision expirePending(Payment payment, boolean operationInFlight, Instant now) {
    boolean due = payment.status() == PaymentStatus.PENDING
        && !operationInFlight
        && payment.expiresAt() != null
        && !now.isBefore(payment.expiresAt())
        && payment.gatewayStatus() == GatewayStatus.INITIATED;
    if (!due) {
      return Decision.unchanged(payment);
    }
    return new Decision(
        payment.with(p -> {
          p.status = PaymentStatus.EXPIRED;
          p.actionUrl = null;
        }),
        List.of(new Notify(EventType.PAYMENT_EXPIRED)));
  }

  /**
   * A hold whose time ran out. The gateway may show it as authorized forever after the bank released the money, so
   * Mizan ends it itself and tries to void it.
   */
  public static Decision expireHold(Payment payment, boolean operationInFlight, Instant now) {
    boolean due = payment.status() == PaymentStatus.AUTHORIZED
        && !operationInFlight
        && payment.holdEndsAt() != null
        && !now.isBefore(payment.holdEndsAt())
        && payment.gatewayStatus() == GatewayStatus.AUTHORIZED;
    if (!due) {
      return Decision.unchanged(payment);
    }
    boolean voidOnce = !payment.followUpsStarted().contains(FollowUp.AUTO_VOID);
    Payment expired = payment.with(p -> {
      p.status = PaymentStatus.EXPIRED;
      p.pendingFollowUp = voidOnce ? FollowUp.AUTO_VOID : null;
    });
    return new Decision(
        expired,
        voidOnce
            ? List.of(new Notify(EventType.PAYMENT_EXPIRED), new FollowUpRequested(FollowUp.AUTO_VOID))
            : List.of(new Notify(EventType.PAYMENT_EXPIRED)));
  }
}
