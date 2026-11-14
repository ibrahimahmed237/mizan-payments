package dev.mizan.core.operation;

import dev.mizan.core.payment.Payment;
import dev.mizan.core.payment.PaymentStatus;
import dev.mizan.core.sync.Decision;
import dev.mizan.core.sync.Effect.Notify;
import dev.mizan.core.sync.EventType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The checks before a capture, a cancel, a refund, and a new payment for an order. Each returns the refusal, or
 * nothing when the command may start. The caller runs them after a fresh sync and under the payment's lock.
 */
public final class CommandRules {

  private CommandRules() {}

  public static Optional<Refusal> capture(Payment payment, long amount, boolean operationInFlight, Instant now) {
    if (payment.status() != PaymentStatus.AUTHORIZED) {
      return Optional.of(Refusal.conflict("not_capturable", "Only an authorized payment can be captured."));
    }
    if (operationInFlight) {
      return Optional.of(inProgress());
    }
    if (payment.holdEndsAt() != null && !now.isBefore(payment.holdEndsAt())) {
      return Optional.of(Refusal.conflict("hold_expired", "The hold on this payment has ended."));
    }
    if (amount <= 0 || amount > payment.amount()) {
      return Optional.of(Refusal.invalid("invalid_amount", "The amount must be above zero and at most the payment's amount."));
    }
    if (amount < payment.amount() && !payment.gateway().supportsPartialCapture()) {
      return Optional.of(Refusal.invalid("partial_capture_not_supported", "This payment method captures the full amount only."));
    }
    return Optional.empty();
  }

  /** What a cancel does when it is allowed. */
  public enum Cancel {
    /** Void the hold at the gateway. */
    VOID_AT_GATEWAY,
    /** The customer has not paid: cancel here, no call needed. */
    CANCEL_HERE
  }

  /** The refusal, or nothing. When nothing, {@link #cancelKind} says what to do. */
  public static Optional<Refusal> cancel(Payment payment, boolean operationInFlight) {
    return switch (payment.status()) {
      case AUTHORIZED, PENDING -> operationInFlight ? Optional.of(inProgress()) : Optional.empty();
      case CAPTURED -> Optional.of(Refusal.conflict("already_captured", "The money was taken. Use a refund."));
      case FAILED, CANCELED, EXPIRED -> Optional.of(Refusal.conflict("not_cancelable", "The payment has already ended."));
    };
  }

  public static Cancel cancelKind(Payment payment) {
    return payment.status() == PaymentStatus.AUTHORIZED ? Cancel.VOID_AT_GATEWAY : Cancel.CANCEL_HERE;
  }

  /**
   * Cancels a payment that the customer has not paid, without a gateway call. The caller has just seen, in the
   * gateway's own record, that no money was taken. If the customer pays later after all, that is a late capture.
   */
  public static Decision cancelHere(Payment payment) {
    if (payment.status() != PaymentStatus.PENDING) {
      throw new IllegalArgumentException("Only a pending payment is canceled without a call.");
    }
    return new Decision(
        payment.with(p -> {
          p.status = PaymentStatus.CANCELED;
          p.actionUrl = null;
        }),
        List.of(new Notify(EventType.PAYMENT_CANCELED)));
  }

  public static Optional<Refusal> refund(Payment payment, long amount, boolean operationInFlight) {
    if (payment.status() != PaymentStatus.CAPTURED) {
      return Optional.of(Refusal.conflict("not_refundable", "Only a captured payment can be refunded."));
    }
    if (operationInFlight) {
      return Optional.of(inProgress());
    }
    if (amount <= 0 || amount > payment.refundable()) {
      return Optional.of(Refusal.invalid(
          "refund_too_large", "The amount must be above zero and at most what can still be refunded: " + payment.refundable() + " halalas."));
    }
    return Optional.empty();
  }

  /**
   * One order, one payment that counts.
   *
   * @param otherHasUnknownResult another payment of the order is pending and its result is not known
   * @param otherHasMoney another payment of the order is authorized, or captured and not fully refunded
   */
  public static Optional<Refusal> newPaymentForOrder(boolean otherHasUnknownResult, boolean otherHasMoney) {
    if (otherHasMoney) {
      return Optional.of(Refusal.conflict("reference_already_paid", "This order already has a payment that holds or has the money."));
    }
    if (otherHasUnknownResult) {
      return Optional.of(Refusal.conflict(
          "previous_payment_unresolved", "An earlier payment of this order has no known result yet. Try again in a minute."));
    }
    return Optional.empty();
  }

  /** Whether a payment holds or has money for its order. */
  public static boolean hasMoney(Payment payment) {
    return payment.status() == PaymentStatus.AUTHORIZED
        || (payment.status() == PaymentStatus.CAPTURED && payment.refundable() > 0);
  }

  private static Refusal inProgress() {
    return Refusal.conflict("operation_in_progress", "Another operation on this payment has not finished yet.");
  }
}
