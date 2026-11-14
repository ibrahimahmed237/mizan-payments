package dev.mizan.app.adapter.out.db;

import dev.mizan.app.application.model.OperationRecord;
import dev.mizan.app.application.model.OperationStatus;
import dev.mizan.app.application.model.Origin;
import dev.mizan.app.application.port.Operations;
import dev.mizan.core.payment.OperationType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The operations table, in plain SQL. */
@Repository
class JdbcOperations implements Operations {

  private static final String SELECT = """
      SELECT id, payment_id, type, status, origin, amount, attempts, last_attempt_at, result_code, reason, created_at, finished_at
      FROM operations
      """;

  private final JdbcClient jdbc;

  JdbcOperations(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(OperationRecord operation) {
    jdbc.sql("""
        INSERT INTO operations (
          id, payment_id, type, status, origin, amount, attempts, last_attempt_at, result_code, reason, created_at, finished_at)
        VALUES (
          :id, :paymentId, :type, :status, :origin, :amount, :attempts, :lastAttemptAt, :resultCode, :reason, :createdAt, :finishedAt)
        """)
        .param("id", operation.id())
        .param("paymentId", operation.paymentId())
        .param("type", operation.type().name())
        .param("status", operation.status().name())
        .param("origin", operation.origin().name())
        .param("amount", operation.amount())
        .param("attempts", operation.attempts())
        .param("lastAttemptAt", Sql.time(operation.lastAttemptAt()))
        .param("resultCode", operation.resultCode())
        .param("reason", operation.reason())
        .param("createdAt", Sql.time(operation.createdAt()))
        .param("finishedAt", Sql.time(operation.finishedAt()))
        .update();
  }

  @Override
  public Optional<OperationRecord> find(UUID id) {
    return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(JdbcOperations::read).optional();
  }

  @Override
  public Optional<OperationRecord> inFlight(UUID paymentId) {
    return jdbc.sql(SELECT + " WHERE payment_id = ? AND status = 'IN_FLIGHT'").param(paymentId).query(JdbcOperations::read).optional();
  }

  @Override
  public List<OperationRecord> ofPayment(UUID paymentId, OperationType type) {
    if (type == null) {
      return jdbc.sql(SELECT + " WHERE payment_id = ? ORDER BY seq").param(paymentId).query(JdbcOperations::read).list();
    }
    return jdbc.sql(SELECT + " WHERE payment_id = ? AND type = ? ORDER BY seq")
        .param(paymentId).param(type.name()).query(JdbcOperations::read).list();
  }

  @Override
  public void finish(OperationRecord finished) {
    int rows = jdbc.sql("""
        UPDATE operations SET status = ?, result_code = ?, finished_at = ?
        WHERE id = ? AND status = 'IN_FLIGHT'
        """)
        .param(finished.status().name())
        .param(finished.resultCode())
        .param(Sql.time(finished.finishedAt()))
        .param(finished.id())
        .update();
    if (rows != 1) {
      throw new IllegalStateException("Operation " + finished.id() + " is not in flight, so it cannot be finished.");
    }
  }

  @Override
  public void attempt(UUID id, int attempts, Instant at) {
    int rows = jdbc.sql("UPDATE operations SET attempts = ?, last_attempt_at = ? WHERE id = ? AND status = 'IN_FLIGHT'")
        .param(attempts).param(Sql.time(at)).param(id).update();
    if (rows != 1) {
      throw new IllegalStateException("Operation " + id + " is not in flight, so it cannot be sent again.");
    }
  }

  private static OperationRecord read(ResultSet row, int n) throws SQLException {
    return new OperationRecord(
        Sql.uuid(row, "id"),
        Sql.uuid(row, "payment_id"),
        OperationType.valueOf(row.getString("type")),
        OperationStatus.valueOf(row.getString("status")),
        Origin.valueOf(row.getString("origin")),
        row.getLong("amount"),
        row.getInt("attempts"),
        Sql.instant(row, "last_attempt_at"),
        row.getString("result_code"),
        row.getString("reason"),
        Sql.instant(row, "created_at"),
        Sql.instant(row, "finished_at"));
  }
}
