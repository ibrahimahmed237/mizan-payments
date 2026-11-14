package dev.mizan.app.application.port;

import dev.mizan.core.sync.EventType;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Events for the store, written in the same transaction as the change they tell. */
public interface Outbox {

  /**
   * @param paymentId the payment the event is about; null when it is about none
   * @param data the event's {@code data} object, field by field, in order. See {@link dev.mizan.app.application.view.EventData}
   */
  void add(EventType type, UUID paymentId, Map<String, Object> data, Instant at);
}
