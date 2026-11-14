package dev.mizan.app.adapter.out.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Small helpers shared by the repositories. */
final class Sql {

  private Sql() {}

  /** An instant as a query parameter. The PostgreSQL driver takes {@code OffsetDateTime}, not {@code Instant}. */
  static OffsetDateTime time(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  static Instant instant(ResultSet row, String column) throws SQLException {
    OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  static UUID uuid(ResultSet row, String column) throws SQLException {
    return row.getObject(column, UUID.class);
  }

  /** A lock is worth nothing outside a transaction: it would be gone before the next statement. */
  static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("A lock was asked for outside a transaction.");
    }
  }
}
