package dev.mizan.app.adapter.out.db;

import dev.mizan.app.application.model.LineRecord;
import dev.mizan.app.application.model.LineStatus;
import dev.mizan.app.application.model.SettlementFileRecord;
import dev.mizan.app.application.port.Settlements;
import dev.mizan.core.payment.Gateway;
import dev.mizan.core.settlement.LineType;
import dev.mizan.core.settlement.SettlementLine;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The settlement files and lines tables, in plain SQL. */
@Repository
class JdbcSettlements implements Settlements {

  private static final String FILE_COLUMNS = "id, gateway, payout_id, payout_date, total_net, line_count, sha256, created_at";
  private static final String LINE_COLUMNS = """
      id, file_id, line_number, gateway, transaction_id, gateway_payment_id, type, type_text, gross, fee, net, occurred_at,
      status, payment_id, synced
      """;
  /** Lines are written in batches of this size. */
  private static final int BATCH = 1_000;

  private final JdbcClient jdbc;
  private final JdbcTemplate template;

  JdbcSettlements(JdbcClient jdbc, JdbcTemplate template) {
    this.jdbc = jdbc;
    this.template = template;
  }

  @Override
  public boolean insert(SettlementFileRecord file, List<SettlementLine> lines, Instant now) {
    int rows = jdbc.sql("""
        INSERT INTO settlement_files (id, gateway, payout_id, payout_date, total_net, line_count, sha256, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (gateway, payout_id) DO NOTHING
        """)
        .param(file.id()).param(file.gateway().name()).param(file.payoutId()).param(file.payoutDate()).param(file.totalNet())
        .param(file.lineCount()).param(file.sha256()).param(Sql.time(now))
        .update();
    if (rows == 0) {
      return false;
    }
    List<Object[]> values = new ArrayList<>(Math.min(lines.size(), BATCH));
    for (SettlementLine line : lines) {
      values.add(new Object[] {
          UUID.randomUUID(), file.id(), line.lineNumber(), file.gateway().name(), line.transactionId(), line.gatewayPaymentId(),
          line.type().name(), line.typeText(), line.gross(), line.fee(), line.net(), Sql.time(line.occurredAt()), Sql.time(now)});
      if (values.size() == BATCH) {
        writeLines(values);
        values.clear();
      }
    }
    if (!values.isEmpty()) {
      writeLines(values);
    }
    return true;
  }

  private void writeLines(List<Object[]> values) {
    template.batchUpdate("""
        INSERT INTO settlement_lines (
          id, file_id, line_number, gateway, transaction_id, gateway_payment_id, type, type_text, gross, fee, net, occurred_at,
          status, next_attempt_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NEW', ?)
        """, values);
  }

  @Override
  public Optional<SettlementFileRecord> findFile(Gateway gateway, String payoutId) {
    return jdbc.sql("SELECT " + FILE_COLUMNS + " FROM settlement_files WHERE gateway = ? AND payout_id = ?")
        .param(gateway.name()).param(payoutId).query(JdbcSettlements::file).optional();
  }

  @Override
  public Optional<SettlementFileRecord> file(UUID id) {
    return jdbc.sql("SELECT " + FILE_COLUMNS + " FROM settlement_files WHERE id = ?").param(id).query(JdbcSettlements::file).optional();
  }

  @Override
  public List<SettlementFileRecord> files(int limit) {
    return jdbc.sql("SELECT " + FILE_COLUMNS + " FROM settlement_files ORDER BY seq DESC LIMIT ?").param(limit).query(JdbcSettlements::file).list();
  }

  @Override
  public Map<LineStatus, Long> progress(UUID fileId) {
    Map<LineStatus, Long> counts = new EnumMap<>(LineStatus.class);
    jdbc.sql("SELECT status, count(*) AS n FROM settlement_lines WHERE file_id = ? GROUP BY status").param(fileId)
        .query((row, n) -> counts.put(LineStatus.valueOf(row.getString("status")), row.getLong("n"))).list();
    return counts;
  }

  @Override
  public Optional<LineRecord> line(UUID id) {
    return jdbc.sql("SELECT " + LINE_COLUMNS + " FROM settlement_lines WHERE id = ?").param(id).query(JdbcSettlements::line).optional();
  }

  @Override
  public Optional<LineRecord> lockLine(UUID id) {
    Sql.requireTransaction();
    return jdbc.sql("SELECT " + LINE_COLUMNS + " FROM settlement_lines WHERE id = ? FOR UPDATE").param(id).query(JdbcSettlements::line).optional();
  }

  @Override
  public boolean seenBefore(LineRecord line) {
    // By the order lines were written in, not by the order they are matched in: the answer is the same whatever the
    // order of the workers.
    return jdbc.sql("""
        SELECT EXISTS (
          SELECT 1 FROM settlement_lines earlier, settlement_lines this
          WHERE this.id = ? AND earlier.gateway = this.gateway AND earlier.transaction_id = this.transaction_id AND earlier.seq < this.seq)
        """)
        .param(line.id()).query(Boolean.class).single();
  }

  @Override
  public List<UUID> claimDue(Instant now, Instant leaseEnds, int limit) {
    return jdbc.sql("""
        UPDATE settlement_lines SET next_attempt_at = ?
        WHERE id IN (
          SELECT id FROM settlement_lines WHERE next_attempt_at <= ? ORDER BY seq LIMIT ? FOR UPDATE SKIP LOCKED)
        RETURNING id
        """)
        .param(Sql.time(leaseEnds)).param(Sql.time(now)).param(limit).query(UUID.class).list();
  }

  @Override
  public void needsSync(UUID id, UUID paymentId, Instant now) {
    jdbc.sql("UPDATE settlement_lines SET status = 'NEEDS_SYNC', payment_id = ? WHERE id = ?").param(paymentId).param(id).update();
  }

  @Override
  public void synced(UUID id) {
    jdbc.sql("UPDATE settlement_lines SET synced = true WHERE id = ?").param(id).update();
  }

  @Override
  public void retryAt(UUID id, Instant at) {
    jdbc.sql("UPDATE settlement_lines SET next_attempt_at = ? WHERE id = ? AND status IN ('NEW', 'NEEDS_SYNC')").param(Sql.time(at)).param(id).update();
  }

  @Override
  public void handled(UUID id, LineStatus status, UUID paymentId, Instant now) {
    int rows = jdbc.sql("""
        UPDATE settlement_lines SET status = ?, payment_id = ?, next_attempt_at = NULL, handled_at = ?
        WHERE id = ? AND status IN ('NEW', 'NEEDS_SYNC', 'SUSPENSE')
        """)
        .param(status.name()).param(paymentId).param(Sql.time(now)).param(id).update();
    if (rows != 1) {
      throw new IllegalStateException("Settlement line " + id + " was handled before.");
    }
  }

  @Override
  public void accepted(UUID id) {
    jdbc.sql("UPDATE settlement_lines SET status = 'ACCEPTED' WHERE id = ? AND status = 'SUSPENSE'").param(id).update();
  }

  private static SettlementFileRecord file(ResultSet row, int n) throws SQLException {
    return new SettlementFileRecord(
        Sql.uuid(row, "id"), Gateway.valueOf(row.getString("gateway")), row.getString("payout_id"),
        row.getObject("payout_date", LocalDate.class), row.getLong("total_net"), row.getInt("line_count"), row.getString("sha256"),
        Sql.instant(row, "created_at"));
  }

  private static LineRecord line(ResultSet row, int n) throws SQLException {
    var line = new SettlementLine(
        row.getInt("line_number"), row.getString("transaction_id"), row.getString("gateway_payment_id"),
        LineType.valueOf(row.getString("type")), row.getString("type_text"), row.getLong("gross"), row.getLong("fee"), row.getLong("net"),
        Sql.instant(row, "occurred_at"));
    return new LineRecord(
        Sql.uuid(row, "id"), Sql.uuid(row, "file_id"), Gateway.valueOf(row.getString("gateway")), line,
        LineStatus.valueOf(row.getString("status")), Sql.uuid(row, "payment_id"), row.getBoolean("synced"));
  }
}
