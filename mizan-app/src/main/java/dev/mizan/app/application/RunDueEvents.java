package dev.mizan.app.application;

import dev.mizan.app.application.model.OutboxEvent;
import dev.mizan.app.application.port.Outbox;
import dev.mizan.app.application.port.StoreEndpoint;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * One round of the event sender: send the events whose time has come, each with its own signature. A store that
 * does not answer gets the event again later: after 1 min, 5 min, 30 min, 2 h, 6 h and 24 h. Then the event waits
 * for a person. See docs/lld/api.md, "Events sent to the store".
 *
 * <p>No transaction is open while the store is called (rule R5): the events are taken with a lease, sent, and the
 * result of each is written down on its own.
 */
@Service
public class RunDueEvents {

  /** The waits before each new try, after a failed one. */
  public static final List<Duration> RETRIES = List.of(
      Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofHours(6), Duration.ofHours(24));

  /** Longer than a call to the store can take, so a taken event is not sent twice at once. */
  public static final Duration LEASE = Duration.ofSeconds(60);

  private final Outbox outbox;
  private final StoreEndpoint store;
  private final InstantSource clock;

  public RunDueEvents(Outbox outbox, StoreEndpoint store, InstantSource clock) {
    this.outbox = outbox;
    this.store = store;
    this.clock = clock;
  }

  /** @return how many events were taken */
  public int handle(int limit) {
    Instant now = clock.instant();
    List<OutboxEvent> due = outbox.claimDue(now, now.plus(LEASE), limit);
    for (OutboxEvent event : due) {
      if (!store.configured()) {
        outbox.skipped(event.id());
        continue;
      }
      Instant sentAt = clock.instant();
      StoreEndpoint.Delivery delivery = store.send(event.id(), sentAt.getEpochSecond(), event.payload());
      int attempts = event.attempts() + 1;
      if (delivery.delivered()) {
        outbox.delivered(event.id(), attempts, clock.instant());
      } else if (attempts <= RETRIES.size()) {
        outbox.retryAt(event.id(), attempts, clock.instant().plus(RETRIES.get(attempts - 1)), delivery.error());
      } else {
        outbox.failed(event.id(), attempts, delivery.error());
      }
    }
    return due.size();
  }
}
