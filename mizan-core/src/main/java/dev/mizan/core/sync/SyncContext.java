package dev.mizan.core.sync;

import java.time.Duration;
import java.util.Objects;

/**
 * The little that {@link Sync} cannot see in the payment itself.
 *
 * @param lateCapture what to do with money taken on a dead payment
 * @param holdWindow how long a hold can be captured
 * @param orderHasOtherMoney another payment of the same order already holds or has money
 */
public record SyncContext(LateCapturePolicy lateCapture, Duration holdWindow, boolean orderHasOtherMoney) {

  public SyncContext {
    Objects.requireNonNull(lateCapture, "lateCapture");
    Objects.requireNonNull(holdWindow, "holdWindow");
  }
}
