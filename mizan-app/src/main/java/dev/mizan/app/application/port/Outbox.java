package dev.mizan.app.application.port;

import dev.mizan.app.application.model.OutboxEvent;
import dev.mizan.core.sync.EventType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Events for the store, written in the same transaction as the change they tell, and sent later. */
public interface Outbox {

  /**
   * @param paymentId the payment the event is about; null when it is about none
   * @param data the event's {@code data} object, field by field, in order. See {@link dev.mizan.app.application.view.EventData}
   */
  void add(EventType type, UUID paymentId, Map<String, Object> data, Instant at);

  /** Takes up to {@code limit} waiting events whose time has come, and pushes their time to {@code leaseEnds}. */
  List<OutboxEvent> claimDue(Instant now, Instant leaseEnds, int limit);

  void delivered(UUID id, int attempts, Instant at);

  void retryAt(UUID id, int attempts, Instant at, String error);

  /** Sent as many times as it may be. It waits for a person now. */
  void failed(UUID id, int attempts, String error);

  /** No store address is set: the event is kept and not sent. */
  void skipped(UUID id);

  Optional<OutboxEvent> find(UUID id);

  /** Newest first. Each filter may be null. */
  List<OutboxEvent> list(String status, UUID paymentId, int limit);

  /**
   * Sends the event again, from the start of the retry times.
   *
   * @return false when it is waiting already
   */
  boolean redeliver(UUID id, Instant now);
}
