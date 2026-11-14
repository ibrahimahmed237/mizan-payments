package dev.mizan.app.adapter.out.db;

import dev.mizan.app.application.port.Outbox;
import dev.mizan.core.sync.EventType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** The outbox table. The body of an event is made here, once, and sent later exactly as written. */
@Repository
class JdbcOutbox implements Outbox {

  private final JdbcClient jdbc;
  private final JsonMapper json;

  JdbcOutbox(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public void add(EventType type, UUID paymentId, Map<String, Object> data, Instant at) {
    UUID id = UUID.randomUUID();
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("id", id);
    event.put("type", type.wireName());
    event.put("created_at", at);
    event.put("data", data);

    jdbc.sql("""
        INSERT INTO outbox_events (id, type, payment_id, payload, status, next_attempt_at, created_at)
        VALUES (?, ?, ?, ?, 'WAITING', ?, ?)
        """)
        .param(id).param(type.wireName()).param(paymentId).param(json.writeValueAsString(event))
        .param(Sql.time(at)).param(Sql.time(at))
        .update();
  }
}
