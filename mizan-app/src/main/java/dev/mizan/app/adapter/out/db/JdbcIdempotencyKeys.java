package dev.mizan.app.adapter.out.db;

import dev.mizan.app.application.model.IdempotencyRecord;
import dev.mizan.app.application.port.IdempotencyKeys;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The idempotency keys table. Only the id of what a key made is stored, never an answer. */
@Repository
class JdbcIdempotencyKeys implements IdempotencyKeys {

  private final JdbcClient jdbc;

  JdbcIdempotencyKeys(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<IdempotencyRecord> find(String key) {
    return jdbc.sql("SELECT key, request_hash, payment_id, operation_id FROM idempotency_keys WHERE key = ?")
        .param(key)
        .query((row, n) -> new IdempotencyRecord(
            row.getString("key"), row.getString("request_hash"), Sql.uuid(row, "payment_id"), Sql.uuid(row, "operation_id")))
        .optional();
  }

  @Override
  public boolean save(IdempotencyRecord record, Instant now, Instant expiresAt) {
    Sql.requireTransaction();
    // A key past its time is free again.
    jdbc.sql("DELETE FROM idempotency_keys WHERE key = ? AND expires_at <= ?").param(record.key()).param(Sql.time(now)).update();
    // If another transaction is inserting the same key, this statement waits for it, then finds the key and inserts nothing.
    return jdbc.sql("""
        INSERT INTO idempotency_keys (key, request_hash, payment_id, operation_id, created_at, expires_at)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT (key) DO NOTHING
        """)
        .param(record.key()).param(record.requestHash()).param(record.paymentId()).param(record.operationId())
        .param(Sql.time(now)).param(Sql.time(expiresAt))
        .update() == 1;
  }
}
