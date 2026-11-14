package dev.mizan.app.adapter.in.worker;

import dev.mizan.app.application.RunDueLines;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The matching worker: every second it takes the settlement lines that wait. The round itself is {@link RunDueLines}. */
@Component
@ConditionalOnProperty(name = "mizan.workers.enabled", havingValue = "true", matchIfMissing = true)
class MatchingWorker {

  static final int BATCH = 100;

  private static final Logger LOG = LoggerFactory.getLogger(MatchingWorker.class);

  private final RunDueLines round;

  MatchingWorker(RunDueLines round) {
    this.round = round;
  }

  @Scheduled(fixedDelayString = "${mizan.workers.match-every:1s}")
  void run() {
    try {
      while (round.handle(BATCH) == BATCH) {
        // more are waiting
      }
    } catch (RuntimeException e) {
      LOG.error("A round of the matching worker failed", e);
    }
  }
}
