package dev.mizan.app.adapter.out.db;

import dev.mizan.app.application.model.DiscrepancyRecord;
import dev.mizan.app.application.port.Discrepancies;
import dev.mizan.core.payment.Gateway;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The discrepancies table. Partial unique indexes keep one open discrepancy per line, and of a type per subject. */
@Repository
class JdbcDiscrepancies implements Discrepancies {

  private static final String COLUMNS = """
      id, type, status, payment_id, line_id, gateway, gateway_payment_id, expected, actual, suspense_amount, resolution, note,
      created_at, resolved_at
      """;

  private final JdbcClient jdbc;

  JdbcDiscrepancies(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<UUID> open(String type, UUID paymentId, Long expected, Long actual, Instant at) {
    UUID id = UUID.randomUUID();
    int rows = jdbc.sql("""
        INSERT INTO discrepancies (id, type, payment_id, expected, actual, created_at) VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT (payment_id, type) WHERE status = 'OPEN' AND line_id IS NULL DO NOTHING
        """)
        .param(id).param(type).param(paymentId).param(expected).param(actual).param(Sql.time(at))
        .update();
    return rows == 1 ? Optional.of(id) : Optional.empty();
  }

  @Override
  public Optional<UUID> openForGatewayPayment(String type, Gateway gateway, String gatewayPaymentId, Instant at) {
    UUID id = UUID.randomUUID();
    int rows = jdbc.sql("""
        INSERT INTO discrepancies (id, type, gateway, gateway_payment_id, created_at) VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (gateway, gateway_payment_id, type) WHERE status = 'OPEN' AND payment_id IS NULL AND line_id IS NULL DO NOTHING
        """)
        .param(id).param(type).param(gateway.name()).param(gatewayPaymentId).param(Sql.time(at))
        .update();
    return rows == 1 ? Optional.of(id) : Optional.empty();
  }

  @Override
  public Optional<UUID> openForLine(String type, UUID lineId, UUID paymentId, Long expected, Long actual, long suspenseAmount, Instant at) {
    UUID id = UUID.randomUUID();
    int rows = jdbc.sql("""
        INSERT INTO discrepancies (id, type, line_id, payment_id, expected, actual, suspense_amount, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (line_id) WHERE status = 'OPEN' AND line_id IS NOT NULL DO NOTHING
        """)
        .param(id).param(type).param(lineId).param(paymentId).param(expected).param(actual).param(suspenseAmount).param(Sql.time(at))
        .update();
    return rows == 1 ? Optional.of(id) : Optional.empty();
  }

  @Override
  public Optional<DiscrepancyRecord> find(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM discrepancies WHERE id = ?").param(id).query(JdbcDiscrepancies::read).optional();
  }

  @Override
  public Optional<DiscrepancyRecord> lock(UUID id) {
    Sql.requireTransaction();
    return jdbc.sql("SELECT " + COLUMNS + " FROM discrepancies WHERE id = ? FOR UPDATE").param(id).query(JdbcDiscrepancies::read).optional();
  }

  @Override
  public List<DiscrepancyRecord> openOfType(String type) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM discrepancies WHERE status = 'OPEN' AND type = ? AND line_id IS NULL AND payment_id IS NOT NULL ORDER BY seq")
        .param(type).query(JdbcDiscrepancies::read).list();
  }

  @Override
  public List<DiscrepancyRecord> list(Boolean open, String type, int limit) {
    var where = new ArrayList<String>();
    var values = new ArrayList<Object>();
    if (open != null) {
      where.add("status = ?");
      values.add(open ? "OPEN" : "RESOLVED");
    }
    if (type != null) {
      where.add("type = ?");
      values.add(type);
    }
    values.add(limit);
    String sql = "SELECT " + COLUMNS + " FROM discrepancies" + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where))
        + " ORDER BY seq DESC LIMIT ?";
    return jdbc.sql(sql).params(values).query(JdbcDiscrepancies::read).list();
  }

  @Override
  public void resolve(UUID id, String resolution, String note, Instant at) {
    int rows = jdbc.sql("UPDATE discrepancies SET status = 'RESOLVED', resolution = ?, note = ?, resolved_at = ? WHERE id = ? AND status = 'OPEN'")
        .param(resolution).param(note).param(Sql.time(at)).param(id).update();
    if (rows != 1) {
      throw new IllegalStateException("Discrepancy " + id + " is not open, so it cannot be resolved.");
    }
  }

  private static DiscrepancyRecord read(ResultSet row, int n) throws SQLException {
    long expected = row.getLong("expected");
    Long expectedOrNull = row.wasNull() ? null : expected;
    long actual = row.getLong("actual");
    Long actualOrNull = row.wasNull() ? null : actual;
    return new DiscrepancyRecord(
        Sql.uuid(row, "id"), row.getString("type"), "OPEN".equals(row.getString("status")), Sql.uuid(row, "payment_id"),
        Sql.uuid(row, "line_id"), row.getString("gateway"), row.getString("gateway_payment_id"), expectedOrNull, actualOrNull,
        row.getLong("suspense_amount"), row.getString("resolution"), row.getString("note"), Sql.instant(row, "created_at"),
        Sql.instant(row, "resolved_at"));
  }
}
