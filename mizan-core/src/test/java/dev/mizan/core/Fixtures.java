package dev.mizan.core;

import dev.mizan.core.payment.CaptureMode;
import dev.mizan.core.payment.Gateway;
import dev.mizan.core.payment.GatewayStatus;
import dev.mizan.core.payment.Operation;
import dev.mizan.core.payment.OperationType;
import dev.mizan.core.payment.Payment;
import dev.mizan.core.payment.PaymentStatus;
import dev.mizan.core.sync.LateCapturePolicy;
import dev.mizan.core.sync.SyncContext;
import java.time.Duration;
import java.time.Instant;

/** Payments and operations in the states the tests need. Amounts are in halalas. */
public final class Fixtures {

  public static final Instant NOW = Instant.parse("2026-11-14T09:00:00Z");
  public static final long AMOUNT = 10_000;
  public static final Duration HOLD = Duration.ofDays(7);
  public static final SyncContext REFUND_LATE = new SyncContext(LateCapturePolicy.REFUND, HOLD, false);
  public static final SyncContext KEEP_LATE = new SyncContext(LateCapturePolicy.KEEP, HOLD, false);

  private Fixtures() {}

  /** A new card payment: nothing seen from the gateway yet. */
  public static Payment fresh() {
    return Payment.pending(AMOUNT, "SAR", Gateway.CARD_GATEWAY, CaptureMode.AUTO, NOW.plus(Duration.ofMinutes(30)));
  }

  public static Payment fresh(Gateway gateway, CaptureMode mode) {
    return Payment.pending(AMOUNT, "SAR", gateway, mode, NOW.plus(Duration.ofMinutes(30)));
  }

  /** Waiting for the customer: the gateway has the payment. */
  public static Payment pending() {
    return fresh().with(p -> {
      p.gatewayStatus = GatewayStatus.INITIATED;
      p.actionUrl = "https://bank.example/3ds";
    });
  }

  public static Payment authorized() {
    return pending().with(p -> {
      p.status = PaymentStatus.AUTHORIZED;
      p.gatewayStatus = GatewayStatus.AUTHORIZED;
      p.actionUrl = null;
      p.holdEndsAt = NOW.plus(HOLD);
    });
  }

  public static Payment captured(long captured, long refunded) {
    return pending().with(p -> {
      p.status = PaymentStatus.CAPTURED;
      p.gatewayStatus = GatewayStatus.PAID;
      p.actionUrl = null;
      p.captured = captured;
      p.refunded = refunded;
      p.capturedAt = NOW.minusSeconds(60);
    });
  }

  /** Dead in Mizan: {@code FAILED}, {@code CANCELED} or {@code EXPIRED}, with the gateway's last status as given. */
  public static Payment dead(PaymentStatus status, GatewayStatus last) {
    return fresh().with(p -> {
      p.status = status;
      p.gatewayStatus = last;
    });
  }

  public static Operation inFlight(OperationType type, long amount) {
    return new Operation(type, amount, 1, NOW.minusSeconds(5));
  }
}
