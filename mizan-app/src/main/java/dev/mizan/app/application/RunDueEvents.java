package dev.mizan.app.application;

import dev.mizan.app.application.model.OutboxEvent;
import dev.mizan.app.application.port.Outbox;
import dev.mizan.app.application.port.StoreEndpoint;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class RunDueEvents implements AutoCloseable {

  /** The waits before each new try, after a failed one. */
  public static final List<Duration> RETRIES = List.of(
      Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofHours(6), Duration.ofHours(24));

  /**
   * Longer than a whole round can take (50 events, each call cut off after {@code mizan.http.timeout}, 10 seconds), so another instance does not
   * send a taken event at the same time. If this instance dies, its events are sent again when the lease ends.
   */
  public static final Duration LEASE = Duration.ofMinutes(10);

  private static final Logger LOG = LoggerFactory.getLogger(RunDueEvents.class);

  private final Outbox outbox;
  private final StoreEndpoint store;
  private final SideBySide sends;
  private final InstantSource clock;

  public RunDueEvents(Outbox outbox, StoreEndpoint store, Workers workers, InstantSource clock) {
    this.outbox = outbox;
    this.store = store;
    this.sends = new SideBySide("send", workers.sendParallel());
    this.clock = clock;
  }

  /** @return how many events were taken */
  public int handle(int limit) {
    Instant now = clock.instant();
    List<OutboxEvent> due = outbox.claimDue(now, now.plus(LEASE), limit);
    // Side by side: a send is mostly waiting for the store. The store is not promised any order (it has each
    // payment's version), so nothing is lost by it.
    sends.forEach(due, this::send);
    return due.size();
  }

  private void send(OutboxEvent event) {
    try {
      if (!store.configured()) {
        outbox.skipped(event.id());
        return;
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
    } catch (RuntimeException e) {
      // One event's trouble must not stop the others. Its lease ends, and it is taken again.
      LOG.error("The event sender failed on event {}", event.id(), e);
    }
  }

  @Override
  public void close() {
    sends.close();
  }
}
