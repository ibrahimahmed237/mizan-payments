package dev.mizan.app.adapter.in.worker;

import dev.mizan.app.application.CheckMissingSettlements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the daily check for money that no settlement file has paid out. */
@Component
@ConditionalOnProperty(name = "mizan.workers.enabled", havingValue = "true", matchIfMissing = true)
class MissingSettlementWorker {

  private static final Logger LOG = LoggerFactory.getLogger(MissingSettlementWorker.class);

  private final CheckMissingSettlements check;

  MissingSettlementWorker(CheckMissingSettlements check) {
    this.check = check;
  }

  @Scheduled(cron = "${mizan.workers.missing-check-cron:0 30 5 * * *}", zone = "UTC")
  void run() {
    try {
      var result = check.handle();
      LOG.info("Daily settlement check: {} opened, {} closed", result.opened(), result.closed());
    } catch (RuntimeException e) {
      LOG.error("The daily settlement check failed", e);
    }
  }
}
