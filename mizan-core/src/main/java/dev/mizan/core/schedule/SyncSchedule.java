package dev.mizan.core.schedule;

import dev.mizan.core.payment.Operation;
import dev.mizan.core.payment.Payment;
import java.time.Duration;
import java.time.Instant;

/** When the sync worker looks at a payment next. See docs/lld/workers.md. */
public final class SyncSchedule {

  private static final Duration RETRY = Duration.ofSeconds(30);

  private SyncSchedule() {}

  /**
   * @param inFlight the operation in flight, or null
   * @param createdAt when the payment was created
   * @return the next time, or null when nothing is waiting: a webhook or reconciliation will set it
   */
  public static Instant next(Payment payment, Operation inFlight, Instant createdAt, Duration quietTime, Instant now) {
    if (inFlight != null) {
      Instant decideAt = inFlight.lastAttemptAt().plus(quietTime);
      return decideAt.isAfter(now) ? decideAt : now.plus(RETRY);
    }
    if (payment.pendingFollowUp() != null) {
      return now;
    }
    return switch (payment.status()) {
      case PENDING -> nextWhilePending(payment, createdAt, now);
      case AUTHORIZED -> payment.holdEndsAt() != null && payment.holdEndsAt().isAfter(now)
          ? payment.holdEndsAt()
          : now.plus(RETRY);
      case CAPTURED, FAILED, CANCELED, EXPIRED -> null;
    };
  }

  /** 15 s, 30 s, 1 min, 2 min, then every 5 min, and once exactly when the waiting time ends. */
  private static Instant nextWhilePending(Payment payment, Instant createdAt, Instant now) {
    long age = Duration.between(createdAt, now).toSeconds();
    Duration step = age < 15 ? Duration.ofSeconds(15)
        : age < 45 ? Duration.ofSeconds(30)
        : age < 105 ? Duration.ofMinutes(1)
        : age < 225 ? Duration.ofMinutes(2)
        : Duration.ofMinutes(5);
    Instant next = now.plus(step);
    Instant end = payment.expiresAt();
    if (end == null) {
      return next;
    }
    if (now.isBefore(end)) {
      return next.isAfter(end) ? end : next;
    }
    return now.plus(RETRY); // past its time and still pending: the gateway could not be asked yet
  }

  /** After a fetch failed: 30 s, then doubling, up to 10 minutes. */
  public static Instant afterFailedFetch(int failures, Instant now) {
    long seconds = Math.min(600, 30L << Math.min(Math.max(failures, 1) - 1, 5));
    return now.plusSeconds(seconds);
  }
}
