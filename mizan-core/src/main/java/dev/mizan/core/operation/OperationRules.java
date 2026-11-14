package dev.mizan.core.operation;

import dev.mizan.core.payment.Gateway;
import dev.mizan.core.payment.Operation;
import dev.mizan.core.payment.OperationType;
import dev.mizan.core.payment.Payment;
import dev.mizan.core.payment.PaymentStatus;
import dev.mizan.core.sync.Decision;
import dev.mizan.core.sync.Effect;
import dev.mizan.core.sync.Effect.Notify;
import dev.mizan.core.sync.Effect.OperationFinished;
import dev.mizan.core.sync.EventType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The rules for an operation whose result is not known: when to decide, whether to send again, and what a clear
 * "no" or a lasting silence does to the payment. See docs/lld/payments.md.
 */
public final class OperationRules {

  public static final String NO_EFFECT = "no_effect";
  public static final String GATEWAY_UNREACHABLE = "gateway_unreachable";

  /** How many times a call that is safe to repeat is sent in total. */
  public static final int MAX_ATTEMPTS = 2;

  private OperationRules() {}

  /**
   * Called after a fetch showed that nothing changed. Before one quiet time has passed since the last call, the
   * call may still be on its way, so nothing is decided.
   */
  public static Verdict afterSilence(Operation operation, Gateway gateway, Duration quietTime, Instant now) {
    if (now.isBefore(operation.lastAttemptAt().plus(quietTime))) {
      return Verdict.WAIT;
    }
    return safeToRepeat(operation.type(), gateway) && operation.attempts() < MAX_ATTEMPTS
        ? Verdict.SEND_AGAIN
        : Verdict.NO_EFFECT;
  }

  /**
   * A repeat must not be able to move money twice. A create is never repeated: its token is gone. A refund is
   * repeated only where the gateway takes a refund key.
   */
  public static boolean safeToRepeat(OperationType type, Gateway gateway) {
    return switch (type) {
      case CAPTURE, CANCEL -> true;
      case REFUND -> gateway.hasRefundKey();
      case CREATE -> false;
    };
  }

  /** The operation ended without effect: nothing changed at the gateway and nothing more will be sent. */
  public static Decision noEffect(Payment payment, Operation operation) {
    return failed(payment, operation, NO_EFFECT, operation.type() == OperationType.CREATE ? GATEWAY_UNREACHABLE : null, null);
  }

  /** The gateway gave a clear no to the call. */
  public static Decision refused(Payment payment, Operation operation, String code, String message) {
    return failed(payment, operation, code, code, message);
  }

  /** A create whose answer was lost at a gateway where nothing can be paid without the answer (pay later). */
  public static Decision unreachable(Payment payment, Operation operation) {
    return failed(payment, operation, GATEWAY_UNREACHABLE, GATEWAY_UNREACHABLE, null);
  }

  private static Decision failed(Payment payment, Operation operation, String code, String paymentCode, String message) {
    List<Effect> effects = new ArrayList<>();
    effects.add(new OperationFinished(false, code));
    Payment after = payment;
    if (operation.type() == OperationType.CREATE) {
      // A payment that was canceled meanwhile stays canceled.
      if (payment.status() == PaymentStatus.PENDING) {
        after = payment.with(p -> {
          p.status = PaymentStatus.FAILED;
          p.failureCode = paymentCode;
          p.failureMessage = message;
          p.actionUrl = null;
        });
        effects.add(new Notify(EventType.PAYMENT_FAILED));
      }
    }
    // The store's event for a failed capture, cancel or refund follows from the finished operation.
    return new Decision(after, effects);
  }
}
