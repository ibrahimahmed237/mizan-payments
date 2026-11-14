package dev.mizan.app.application;

import dev.mizan.app.application.port.Outbox;
import dev.mizan.app.application.view.EventView;
import java.time.InstantSource;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Finance reads the events sent to the store, and sends one again. */
@Service
public class EventQueries {

  private static final Set<String> STATUSES = Set.of("WAITING", "DELIVERED", "FAILED", "SKIPPED");

  private final Outbox outbox;
  private final InstantSource clock;

  public EventQueries(Outbox outbox, InstantSource clock) {
    this.outbox = outbox;
    this.clock = clock;
  }

  public List<EventView> list(String status, UUID paymentId) {
    if (status != null && !STATUSES.contains(status)) {
      throw Problem.invalid("invalid_status", "The status must be one of " + STATUSES + ".");
    }
    return outbox.list(status, paymentId, BackofficeQueries.MAX_PAGE).stream().map(EventView::of).toList();
  }

  /** Sends the event again, from the first retry time on. For a store that lost it, or was away too long. */
  public EventView redeliver(UUID id) {
    outbox.find(id).orElseThrow(() -> Problem.notFound("event"));
    if (!outbox.redeliver(id, clock.instant())) {
      throw new Problem(Problem.Kind.CONFLICT, "already_waiting", "This event is waiting to be sent already.");
    }
    return EventView.of(outbox.find(id).orElseThrow());
  }
}
