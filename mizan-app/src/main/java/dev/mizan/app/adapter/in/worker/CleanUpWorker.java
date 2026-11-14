package dev.mizan.app.adapter.in.worker;

import dev.mizan.app.application.CleanUp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the daily clean-up. */
@Component
@ConditionalOnProperty(name = "mizan.workers.enabled", havingValue = "true", matchIfMissing = true)
class CleanUpWorker {

  private static final Logger LOG = LoggerFactory.getLogger(CleanUpWorker.class);

  private final CleanUp cleanUp;

  CleanUpWorker(CleanUp cleanUp) {
    this.cleanUp = cleanUp;
  }

  @Scheduled(cron = "${mizan.workers.clean-up-cron:0 10 4 * * *}", zone = "UTC")
  void run() {
    try {
      var result = cleanUp.handle();
      LOG.info("Clean-up: {} idempotency keys, {} webhooks, {} events deleted", result.idempotencyKeys(), result.webhooks(), result.events());
    } catch (RuntimeException e) {
      LOG.error("The clean-up failed", e);
    }
  }
}
