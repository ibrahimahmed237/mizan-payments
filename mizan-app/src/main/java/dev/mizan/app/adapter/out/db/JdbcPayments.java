package dev.mizan.app.adapter.out.db;

import dev.mizan.app.application.model.PaymentRecord;
import dev.mizan.app.application.port.Payments;
import dev.mizan.core.payment.CaptureMode;
import dev.mizan.core.payment.FollowUp;
import dev.mizan.core.payment.Gateway;
import dev.mizan.core.payment.GatewayStatus;
import dev.mizan.core.payment.Method;
import dev.mizan.core.payment.Payment;
import dev.mizan.core.payment.PaymentStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The payments table, in plain SQL. */
@Repository
class JdbcPayments implements Payments {

  private static final String COLUMNS = """
      id, reference, amount, currency, method, gateway, capture_mode, status, captured_amount, refunded_amount,
      settled_captured, settled_refunded, gateway_payment_id, gateway_status, action_url, return_url, description,
      failure_code, failure_message, scheme, last4, late, follow_up, follow_ups_started, expires_at, hold_ends_at,
      captured_at, last_refund_at, next_sync_at, sync_requested, last_synced_at, sync_failures, version, created_at, updated_at
      """;

  private final JdbcClient jdbc;

  JdbcPayments(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void lockOrder(String reference) {
    Sql.requireTransaction();
    // An advisory lock on a number made from the reference. Two references with the same number only wait for each
    // other; nothing breaks.
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").param(reference).query((row, n) -> 0).list();
  }

  @Override
  public Optional<PaymentRecord> find(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM payments WHERE id = ?").param(id).query(JdbcPayments::read).optional();
  }

  @Override
  public Optional<PaymentRecord> lock(UUID id) {
    Sql.requireTransaction();
    // The order first, then the row: always this order, so two transactions cannot wait for each other.
    Optional<String> reference = jdbc.sql("SELECT reference FROM payments WHERE id = ?").param(id).query(String.class).optional();
    if (reference.isEmpty()) {
      return Optional.empty();
    }
    lockOrder(reference.get());
    return jdbc.sql("SELECT " + COLUMNS + " FROM payments WHERE id = ? FOR UPDATE").param(id).query(JdbcPayments::read).optional();
  }

  @Override
  public List<PaymentRecord> ofOrder(String reference) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM payments WHERE reference = ? ORDER BY created_at, id")
        .param(reference).query(JdbcPayments::read).list();
  }

  @Override
  public Optional<PaymentRecord> findByGatewayId(Gateway gateway, String gatewayPaymentId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM payments WHERE gateway = ? AND gateway_payment_id = ?")
        .param(gateway.name()).param(gatewayPaymentId).query(JdbcPayments::read).optional();
  }

  @Override
  public void requestSync(UUID id, Instant now) {
    // The flag is not written by a sync's own save.
    jdbc.sql("UPDATE payments SET sync_requested = true, next_sync_at = ? WHERE id = ?").param(Sql.time(now)).param(id).update();
  }

  @Override
  public void claim(UUID id, Instant leaseEnds) {
    jdbc.sql("UPDATE payments SET next_sync_at = ?, sync_requested = false WHERE id = ?").param(Sql.time(leaseEnds)).param(id).update();
  }

  @Override
  public List<UUID> claimDue(Instant now, Instant leaseEnds, int limit) {
    // SKIP LOCKED: a payment that another worker, or a request, holds right now is left for the next round.
    // The flag sync_requested stays up: the step takes it down itself, just before its fetch (claim).
    return jdbc.sql("""
        UPDATE payments SET next_sync_at = ?
        WHERE id IN (
          SELECT id FROM payments WHERE next_sync_at <= ? ORDER BY next_sync_at LIMIT ? FOR UPDATE SKIP LOCKED)
        RETURNING id
        """)
        .param(Sql.time(leaseEnds)).param(Sql.time(now)).param(limit).query(UUID.class).list();
  }

  @Override
  public void insert(PaymentRecord record) {
    Payment p = record.payment();
    jdbc.sql("""
        INSERT INTO payments (
          id, reference, amount, currency, method, gateway, capture_mode, status, gateway_payment_id, return_url,
          description, expires_at, next_sync_at, version, created_at, updated_at)
        VALUES (
          :id, :reference, :amount, :currency, :method, :gateway, :captureMode, :status, :gatewayPaymentId, :returnUrl,
          :description, :expiresAt, :nextSyncAt, :version, :createdAt, :updatedAt)
        """)
        .param("id", record.id())
        .param("reference", record.reference())
        .param("amount", p.amount())
        .param("currency", p.currency())
        .param("method", record.method().name())
        .param("gateway", p.gateway().name())
        .param("captureMode", p.captureMode().name())
        .param("status", p.status().name())
        .param("gatewayPaymentId", record.gatewayPaymentId())
        .param("returnUrl", record.returnUrl())
        .param("description", record.description())
        .param("expiresAt", Sql.time(p.expiresAt()))
        .param("nextSyncAt", Sql.time(record.nextSyncAt()))
        .param("version", record.version())
        .param("createdAt", Sql.time(record.createdAt()))
        .param("updatedAt", Sql.time(record.updatedAt()))
        .update();
  }

  @Override
  public void update(PaymentRecord record) {
    Payment p = record.payment();
    int rows = jdbc.sql("""
        UPDATE payments SET
          status = :status, captured_amount = :captured, refunded_amount = :refunded,
          settled_captured = :settledCaptured, settled_refunded = :settledRefunded,
          gateway_payment_id = :gatewayPaymentId, gateway_status = :gatewayStatus, action_url = :actionUrl,
          failure_code = :failureCode, failure_message = :failureMessage, scheme = :scheme, last4 = :last4,
          late = :late, follow_up = :followUp, follow_ups_started = :followUpsStarted,
          expires_at = :expiresAt, hold_ends_at = :holdEndsAt, captured_at = :capturedAt, last_refund_at = :lastRefundAt,
          next_sync_at = :nextSyncAt, last_synced_at = :lastSyncedAt, sync_failures = :syncFailures,
          version = :version, updated_at = :updatedAt
        WHERE id = :id
        """)
        .param("id", record.id())
        .param("status", p.status().name())
        .param("captured", p.captured())
        .param("refunded", p.refunded())
        .param("settledCaptured", record.settledCaptured())
        .param("settledRefunded", record.settledRefunded())
        .param("gatewayPaymentId", record.gatewayPaymentId())
        .param("gatewayStatus", p.gatewayStatus() == null ? null : p.gatewayStatus().name())
        .param("actionUrl", p.actionUrl())
        .param("failureCode", p.failureCode())
        .param("failureMessage", p.failureMessage())
        .param("scheme", p.scheme())
        .param("last4", p.last4())
        .param("late", p.late())
        .param("followUp", p.pendingFollowUp() == null ? null : p.pendingFollowUp().name())
        .param("followUpsStarted", p.followUpsStarted().stream().map(Enum::name).sorted().collect(Collectors.joining(",")))
        .param("expiresAt", Sql.time(p.expiresAt()))
        .param("holdEndsAt", Sql.time(p.holdEndsAt()))
        .param("capturedAt", Sql.time(p.capturedAt()))
        .param("lastRefundAt", Sql.time(p.lastRefundAt()))
        .param("nextSyncAt", Sql.time(record.nextSyncAt()))
        .param("lastSyncedAt", Sql.time(record.lastSyncedAt()))
        .param("syncFailures", record.syncFailures())
        .param("version", record.version())
        .param("updatedAt", Sql.time(record.updatedAt()))
        .update();
    if (rows != 1) {
      throw new IllegalStateException("Payment " + record.id() + " was not saved: it does not exist.");
    }
  }

  @Override
  public List<PaymentRecord> unsettledSince(Instant before) {
    return jdbc.sql("SELECT " + COLUMNS + """
         FROM payments
        WHERE (captured_amount > settled_captured AND captured_at <= ?)
           OR (refunded_amount > settled_refunded AND last_refund_at <= ?)
        ORDER BY captured_at, id
        """)
        .param(Sql.time(before)).param(Sql.time(before)).query(JdbcPayments::read).list();
  }

  @Override
  public List<PaymentRecord> list(String reference, PaymentStatus status, Place before, int limit) {
    var where = new ArrayList<String>();
    var values = new ArrayList<Object>();
    if (reference != null) {
      where.add("reference = ?");
      values.add(reference);
    }
    if (status != null) {
      where.add("status = ?");
      values.add(status.name());
    }
    if (before != null) {
      where.add("(created_at, id) < (?, ?)");
      values.add(Sql.time(before.createdAt()));
      values.add(before.id());
    }
    values.add(limit);
    String sql = "SELECT " + COLUMNS + " FROM payments"
        + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where))
        + " ORDER BY created_at DESC, id DESC LIMIT ?";
    return jdbc.sql(sql).params(values).query(JdbcPayments::read).list();
  }

  private static PaymentRecord read(ResultSet row, int n) throws SQLException {
    String gatewayStatus = row.getString("gateway_status");
    String followUp = row.getString("follow_up");
    var payment = new Payment(
        row.getLong("amount"),
        row.getString("currency"),
        Gateway.valueOf(row.getString("gateway")),
        CaptureMode.valueOf(row.getString("capture_mode")),
        PaymentStatus.valueOf(row.getString("status")),
        row.getLong("captured_amount"),
        row.getLong("refunded_amount"),
        gatewayStatus == null ? null : GatewayStatus.valueOf(gatewayStatus),
        row.getString("action_url"),
        row.getString("failure_code"),
        row.getString("failure_message"),
        row.getString("scheme"),
        row.getString("last4"),
        row.getBoolean("late"),
        followUp == null ? null : FollowUp.valueOf(followUp),
        followUps(row.getString("follow_ups_started")),
        Sql.instant(row, "expires_at"),
        Sql.instant(row, "hold_ends_at"),
        Sql.instant(row, "captured_at"),
        Sql.instant(row, "last_refund_at"));
    return new PaymentRecord(
        Sql.uuid(row, "id"),
        row.getString("reference"),
        Method.valueOf(row.getString("method")),
        row.getString("description"),
        row.getString("return_url"),
        row.getString("gateway_payment_id"),
        payment,
        row.getLong("settled_captured"),
        row.getLong("settled_refunded"),
        Sql.instant(row, "next_sync_at"),
        row.getBoolean("sync_requested"),
        Sql.instant(row, "last_synced_at"),
        row.getInt("sync_failures"),
        row.getInt("version"),
        Sql.instant(row, "created_at"),
        Sql.instant(row, "updated_at"));
  }

  private static Set<FollowUp> followUps(String list) {
    Set<FollowUp> started = EnumSet.noneOf(FollowUp.class);
    for (String name : list.split(",")) {
      if (!name.isEmpty()) {
        started.add(FollowUp.valueOf(name));
      }
    }
    return started;
  }
}
