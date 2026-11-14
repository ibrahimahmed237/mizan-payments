package dev.mizan.app.adapter.out.db;

import dev.mizan.app.application.model.OutboxEvent;
import dev.mizan.app.application.port.Outbox;
import dev.mizan.core.sync.EventType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** The outbox table. The body of an event is made here, once, and sent later exactly as written. */
@Repository
class JdbcOutbox implements Outbox {

  private static final String COLUMNS =
      "id, type, payment_id, payload, status, attempts, next_attempt_at, last_error, created_at, delivered_at";

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

  @Override
  public List<OutboxEvent> claimDue(Instant now, Instant leaseEnds, int limit) {
    return jdbc.sql("""
        UPDATE outbox_events SET next_attempt_at = ?
        WHERE id IN (
          SELECT id FROM outbox_events WHERE status = 'WAITING' AND next_attempt_at <= ? ORDER BY seq LIMIT ? FOR UPDATE SKIP LOCKED)
        RETURNING
        """ + COLUMNS)
        .param(Sql.time(leaseEnds)).param(Sql.time(now)).param(limit).query(JdbcOutbox::read).list();
  }

  @Override
  public void delivered(UUID id, int attempts, Instant at) {
    jdbc.sql("UPDATE outbox_events SET status = 'DELIVERED', attempts = ?, delivered_at = ?, next_attempt_at = NULL, last_error = NULL WHERE id = ?")
        .param(attempts).param(Sql.time(at)).param(id).update();
  }

  @Override
  public void retryAt(UUID id, int attempts, Instant at, String error) {
    jdbc.sql("UPDATE outbox_events SET attempts = ?, next_attempt_at = ?, last_error = ? WHERE id = ?")
        .param(attempts).param(Sql.time(at)).param(error).param(id).update();
  }

  @Override
  public void failed(UUID id, int attempts, String error) {
    jdbc.sql("UPDATE outbox_events SET status = 'FAILED', attempts = ?, next_attempt_at = NULL, last_error = ? WHERE id = ?")
        .param(attempts).param(error).param(id).update();
  }

  @Override
  public void skipped(UUID id) {
    jdbc.sql("UPDATE outbox_events SET status = 'SKIPPED', next_attempt_at = NULL WHERE id = ?").param(id).update();
  }

  @Override
  public Optional<OutboxEvent> find(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM outbox_events WHERE id = ?").param(id).query(JdbcOutbox::read).optional();
  }

  @Override
  public List<OutboxEvent> list(String status, UUID paymentId, int limit) {
    var where = new ArrayList<String>();
    var values = new ArrayList<Object>();
    if (status != null) {
      where.add("status = ?");
      values.add(status);
    }
    if (paymentId != null) {
      where.add("payment_id = ?");
      values.add(paymentId);
    }
    values.add(limit);
    String sql = "SELECT " + COLUMNS + " FROM outbox_events" + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where))
        + " ORDER BY seq DESC LIMIT ?";
    return jdbc.sql(sql).params(values).query(JdbcOutbox::read).list();
  }

  @Override
  public boolean redeliver(UUID id, Instant now) {
    return jdbc.sql("""
        UPDATE outbox_events SET status = 'WAITING', attempts = 0, next_attempt_at = ?, last_error = NULL, delivered_at = NULL
        WHERE id = ? AND status <> 'WAITING'
        """)
        .param(Sql.time(now)).param(id).update() == 1;
  }

  private static OutboxEvent read(ResultSet row, int n) throws SQLException {
    return new OutboxEvent(
        Sql.uuid(row, "id"), row.getString("type"), Sql.uuid(row, "payment_id"), row.getString("payload"), row.getString("status"),
        row.getInt("attempts"), Sql.instant(row, "next_attempt_at"), row.getString("last_error"), Sql.instant(row, "created_at"),
        Sql.instant(row, "delivered_at"));
  }
}
