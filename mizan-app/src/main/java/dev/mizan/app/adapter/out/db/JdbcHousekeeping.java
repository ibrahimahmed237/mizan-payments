package dev.mizan.app.adapter.out.db;

import dev.mizan.app.application.port.Housekeeping;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The daily clean-up's deletes, one batch each. */
@Repository
class JdbcHousekeeping implements Housekeeping {

  private final JdbcClient jdbc;

  JdbcHousekeeping(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public int deleteIdempotencyKeysExpiredBy(Instant now, int batch) {
    return jdbc.sql("DELETE FROM idempotency_keys WHERE key IN (SELECT key FROM idempotency_keys WHERE expires_at <= ? LIMIT ?)")
        .param(Sql.time(now)).param(batch).update();
  }

  @Override
  public int deleteWebhookEventsReceivedBefore(Instant before, int batch) {
    return jdbc.sql("DELETE FROM gateway_events WHERE id IN (SELECT id FROM gateway_events WHERE received_at < ? LIMIT ?)")
        .param(Sql.time(before)).param(batch).update();
  }

  @Override
  public int deleteDoneEventsCreatedBefore(Instant before, int batch) {
    return jdbc.sql("""
        DELETE FROM outbox_events WHERE id IN (
          SELECT id FROM outbox_events WHERE status IN ('DELIVERED', 'SKIPPED') AND created_at < ? LIMIT ?)
        """)
        .param(Sql.time(before)).param(batch).update();
  }
}
