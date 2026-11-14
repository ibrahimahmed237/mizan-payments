package dev.mizan.app.adapter.out.metrics;

import dev.mizan.core.payment.PaymentStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
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
      gauge("mizan.payments", "Payments in this state", () -> payments().byState().getOrDefault(status.name(), 0L))
          .tag("state", status.name().toLowerCase(Locale.ROOT)).register(meters);
    }
    gauge("mizan.payments.pending.oldest.seconds", "Age of the oldest pending payment", () -> {
      Instant oldest = payments().oldestPending();
      return oldest == null ? 0.0 : (double) Duration.between(oldest, clock.instant()).toSeconds();
    }).register(meters);
    gauge("mizan.late_captures", "Payments whose money was taken after they had ended", () -> payments().late()).register(meters);
    gauge("mizan.operations.in_flight", "Operations with no result yet", () -> count("SELECT count(*) FROM operations WHERE status = 'IN_FLIGHT'"))
        .register(meters);
    gauge("mizan.outbox.waiting", "Events for the store not delivered yet", () -> count("SELECT count(*) FROM outbox_events WHERE status = 'WAITING'"))
        .register(meters);
    gauge("mizan.discrepancies.open", "Open discrepancies", () -> count("SELECT count(*) FROM discrepancies WHERE status = 'OPEN'"))
        .register(meters);
    gauge("mizan.ledger.suspense", "Money received and not explained, in halalas", () -> count(
        "SELECT coalesce(sum(CASE WHEN side = 'CREDIT' THEN amount ELSE -amount END), 0) FROM ledger_postings WHERE account = 'suspense'"))
        .register(meters);
  }

  /** What the payments table says now. */
  private record Payments(Map<String, Long> byState, long late, Instant oldestPending, long readAtNanos) {}

  private static final long FRESH_NANOS = Duration.ofSeconds(1).toNanos();

  private volatile Payments lastRead;

  /**
   * One read of the payments table answers every gauge about payments. A scrape asks for all of them in the same
   * moment, so the answer is kept for a second: one read of the largest table per scrape, not one per gauge.
   */
  private Payments payments() {
    Payments known = lastRead;
    if (known != null && System.nanoTime() - known.readAtNanos() < FRESH_NANOS) {
      return known;
    }
    record OfState(String status, long payments, long late, OffsetDateTime oldestPending) {}
    List<OfState> states = jdbc.sql("""
        SELECT status, count(*) AS payments, count(*) FILTER (WHERE late) AS late,
               min(created_at) FILTER (WHERE status = 'PENDING') AS oldest_pending
        FROM payments GROUP BY status
        """)
        .query((row, n) -> new OfState(
            row.getString("status"), row.getLong("payments"), row.getLong("late"), row.getObject("oldest_pending", OffsetDateTime.class)))
        .list();
    Instant oldestPending = states.stream().map(OfState::oldestPending).filter(Objects::nonNull).map(OffsetDateTime::toInstant).findFirst().orElse(null);
    known = new Payments(
        states.stream().collect(Collectors.toMap(OfState::status, OfState::payments)),
        states.stream().mapToLong(OfState::late).sum(), oldestPending, System.nanoTime());
    lastRead = known;
    return known;
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
