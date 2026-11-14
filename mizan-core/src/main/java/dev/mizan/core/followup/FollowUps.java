package dev.mizan.core.followup;

import dev.mizan.core.payment.FollowUp;
import dev.mizan.core.payment.GatewayStatus;
import dev.mizan.core.payment.OperationType;
import dev.mizan.core.payment.Payment;
import dev.mizan.core.payment.PaymentStatus;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * Starts the operation that Mizan owes a payment by itself: an automatic capture, the release of a late hold, the
 * return of a late capture. See docs/lld/payments.md, "Follow-ups".
 *
 * <p>The caller runs this right after a sync, so the payment is as the gateway has it now, and only when no
 * operation is in flight.
 */
public final class FollowUps {

  /**
   * What to do about a payment's waiting follow-up.
   *
   * @param payment the payment with nothing waiting any more
   * @param operation the operation to send, or null when the follow-up no longer applies
   * @param amount the amount of a capture or a refund; zero otherwise
   */
  public record Start(Payment payment, OperationType operation, long amount) {

    public boolean nothingToSend() {
      return operation == null;
    }
  }

  private FollowUps() {}

  public static Start begin(Payment payment, Instant now) {
    FollowUp kind = payment.pendingFollowUp();
    if (kind == null) {
      throw new IllegalArgumentException("The payment has no follow-up waiting.");
    }
    long amount = switch (kind) {
      case AUTO_CAPTURE -> payment.amount();
      case AUTO_REFUND -> payment.refundable();
      case AUTO_VOID -> 0;
    };
    if (!stillApplies(kind, payment, now)) {
      // Dropped, and not counted as started: if the reason comes back, the follow-up may be asked for again.
      return new Start(payment.with(p -> p.pendingFollowUp = null), null, 0);
    }
    Set<FollowUp> started = EnumSet.of(kind);
    started.addAll(payment.followUpsStarted());
    Payment begun = payment.with(p -> {
      p.pendingFollowUp = null;
      p.followUpsStarted = started; // each kind is started once per payment
    });
    return new Start(begun, kind.operation(), amount);
  }

  /** The facts may have changed since the follow-up was asked for. */
  private static boolean stillApplies(FollowUp kind, Payment payment, Instant now) {
    return switch (kind) {
      // The hold must still be there to be captured.
      case AUTO_CAPTURE -> payment.status() == PaymentStatus.AUTHORIZED
          && (payment.holdEndsAt() == null || now.isBefore(payment.holdEndsAt()));
      // There must be money left to return.
      case AUTO_REFUND -> payment.status() == PaymentStatus.CAPTURED && payment.refundable() > 0;
      // The gateway must still show a hold, on a payment that Mizan has ended.
      case AUTO_VOID -> payment.status().isDead() && payment.gatewayStatus() == GatewayStatus.AUTHORIZED;
    };
  }
}
