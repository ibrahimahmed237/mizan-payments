package dev.mizan.app.adapter.in.worker;

import dev.mizan.app.application.RunDueEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The event sender: every second it sends the events whose time has come. The round itself is {@link RunDueEvents}. */
@Component
@ConditionalOnProperty(name = "mizan.workers.enabled", havingValue = "true", matchIfMissing = true)
class EventSender {

  static final int BATCH = 50;

  private static final Logger LOG = LoggerFactory.getLogger(EventSender.class);

  private final RunDueEvents round;

  EventSender(RunDueEvents round) {
    this.round = round;
  }

  @Scheduled(fixedDelayString = "${mizan.workers.send-every:1s}")
  void run() {
    try {
      while (round.handle(BATCH) == BATCH) {
        // more are waiting
      }
    } catch (RuntimeException e) {
      LOG.error("A round of the event sender failed", e);
    }
  }
}
