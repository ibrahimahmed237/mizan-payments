package dev.mizan.app.adapter.out.metrics;

import dev.mizan.core.payment.PaymentStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import java.time.InstantSource;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Numbers read from the database each time they are scraped. Each one has a "worry when" in docs/lld/running.md.
 * A query that fails gives no number (NaN) rather than a wrong one.
 */
@Component
class StateGauges implements MeterBinder {

  private final JdbcClient jdbc;
  private final InstantSource clock;

  StateGauges(JdbcClient jdbc, InstantSource clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  public void bindTo(MeterRegistry meters) {
    for (PaymentStatus status : PaymentStatus.values()) {
      gauge("mizan.payments", "Payments in this state", () -> count("SELECT count(*) FROM payments WHERE status = ?", status.name()))
          .tag("state", status.name().toLowerCase(Locale.ROOT)).register(meters);
    }
    gauge("mizan.payments.pending.oldest.seconds", "Age of the oldest pending payment", () -> {
      var oldest = jdbc.sql("SELECT min(created_at) FROM payments WHERE status = 'PENDING'").query(OffsetDateTime.class).list();
      return oldest.isEmpty() || oldest.get(0) == null ? 0.0 : (double) Duration.between(oldest.get(0).toInstant(), clock.instant()).toSeconds();
    }).register(meters);
    gauge("mizan.operations.in_flight", "Operations with no result yet", () -> count("SELECT count(*) FROM operations WHERE status = 'IN_FLIGHT'"))
        .register(meters);
    gauge("mizan.late_captures", "Payments whose money was taken after they had ended", () -> count("SELECT count(*) FROM payments WHERE late"))
        .register(meters);
    gauge("mizan.outbox.waiting", "Events for the store not delivered yet", () -> count("SELECT count(*) FROM outbox_events WHERE status = 'WAITING'"))
        .register(meters);
    gauge("mizan.discrepancies.open", "Open discrepancies", () -> count("SELECT count(*) FROM discrepancies WHERE status = 'OPEN'"))
        .register(meters);
    gauge("mizan.ledger.suspense", "Money received and not explained, in halalas", () -> count(
        "SELECT coalesce(sum(CASE WHEN side = 'CREDIT' THEN amount ELSE -amount END), 0) FROM ledger_postings WHERE account = 'suspense'"))
        .register(meters);
  }

  private static Gauge.Builder<Supplier<Number>> gauge(String name, String description, Supplier<Number> value) {
    return Gauge.builder(name, () -> {
      try {
        return value.get();
      } catch (RuntimeException e) {
        return Double.NaN;
      }
    }).description(description);
  }

  private double count(String sql, Object... params) {
    var query = jdbc.sql(sql);
    for (Object param : params) {
      query = query.param(param);
    }
    return query.query(Long.class).single();
  }
}
