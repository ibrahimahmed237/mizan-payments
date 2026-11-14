package dev.mizan.app.adapter.in.worker;

import dev.mizan.app.application.RunDuePayments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The sync worker: every second it takes the payments whose time to be looked at has come. It holds no rule; the
 * round itself is {@link RunDuePayments}. Tests switch it off and run rounds by hand, on a clock they move.
 */
@Component
@ConditionalOnProperty(name = "mizan.workers.enabled", havingValue = "true", matchIfMissing = true)
class SyncWorker {

  /** Payments taken in one round. A full round is followed by another at once. */
  static final int BATCH = 50;

  private static final Logger LOG = LoggerFactory.getLogger(SyncWorker.class);

  private final RunDuePayments round;

  SyncWorker(RunDuePayments round) {
    this.round = round;
  }

  @Scheduled(fixedDelayString = "${mizan.workers.sync-every:1s}")
  void run() {
    try {
      while (round.handle(BATCH) == BATCH) {
        // more are waiting
      }
    } catch (RuntimeException e) {
      LOG.error("A round of the sync worker failed", e); // the next round runs all the same
    }
  }
}
