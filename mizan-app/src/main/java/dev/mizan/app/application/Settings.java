package dev.mizan.app.application;

import dev.mizan.core.payment.Method;
import dev.mizan.core.sync.LateCapturePolicy;
import java.time.Duration;
import java.util.List;

/**
 * The settings the use cases read. See docs/lld/running.md.
 *
 * @param maxAmount the largest payment, in halalas
 * @param cardWait how long a customer may take on the bank's page
 * @param payLaterWait how long a customer may take on the provider's page
 * @param holdWindow how long an authorized payment can be captured
 * @param quietTime how long after an operation's last call its result may still arrive
 * @param returnUrlPrefixes allowed starts of a payment's return address
 * @param publicUrl Mizan's own address, for the customer's return
 * @param idempotencyKeysKept how long an idempotency key is remembered
 */
public record Settings(
    long maxAmount,
    Duration cardWait,
    Duration payLaterWait,
    Duration holdWindow,
    LateCapturePolicy lateCapture,
    Duration quietTime,
    List<String> returnUrlPrefixes,
    String publicUrl,
    Duration idempotencyKeysKept) {

  /** The smallest payment: one riyal. */
  public static final long MIN_AMOUNT = 100;

  public Settings {
    returnUrlPrefixes = List.copyOf(returnUrlPrefixes);
  }

  public Duration waitFor(Method method) {
    return method == Method.PAY_LATER ? payLaterWait : cardWait;
  }
}
