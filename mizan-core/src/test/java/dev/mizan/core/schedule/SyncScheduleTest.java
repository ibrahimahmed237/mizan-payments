package dev.mizan.core.schedule;

import static dev.mizan.core.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.core.Fixtures;
import dev.mizan.core.payment.FollowUp;
import dev.mizan.core.payment.GatewayStatus;
import dev.mizan.core.payment.Operation;
import dev.mizan.core.payment.OperationType;
import dev.mizan.core.payment.Payment;
import dev.mizan.core.payment.PaymentStatus;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class SyncScheduleTest {

  private static final Duration QUIET = Duration.ofMinutes(1);

  private static Instant next(Payment payment, Operation inFlight, Instant createdAt, Instant now) {
    return SyncSchedule.next(payment, inFlight, createdAt, QUIET, now);
  }

  @ParameterizedTest(name = "created {0} s ago -> again in {1} s")
  @CsvSource({"0,15", "14,15", "15,30", "44,30", "45,60", "104,60", "105,120", "224,120", "225,300", "900,300"})
  void a_pending_payment_is_looked_at_often_at_first_and_then_every_five_minutes(long ageSeconds, long expectedSeconds) {
    Payment payment = Fixtures.pending().with(p -> p.expiresAt = NOW.plus(Duration.ofHours(2)));

    assertThat(next(payment, null, NOW.minusSeconds(ageSeconds), NOW)).isEqualTo(NOW.plusSeconds(expectedSeconds));
  }

  @Test
  void a_pending_payment_is_looked_at_exactly_when_its_waiting_time_ends() {
    Payment payment = Fixtures.pending().with(p -> p.expiresAt = NOW.plusSeconds(100));

    assertThat(next(payment, null, NOW.minusSeconds(600), NOW)).isEqualTo(NOW.plusSeconds(100));
  }

  @Test
  void a_pending_payment_past_its_time_is_tried_again_soon() {
    Payment payment = Fixtures.pending().with(p -> p.expiresAt = NOW.minusSeconds(1));

    assertThat(next(payment, null, NOW.minusSeconds(3600), NOW)).isEqualTo(NOW.plusSeconds(30));
    assertThat(next(Fixtures.pending().with(p -> p.expiresAt = NOW), null, NOW.minusSeconds(3600), NOW)).isEqualTo(NOW.plusSeconds(30));
  }

  @Test
  void a_pending_payment_without_an_end_keeps_its_rhythm() {
    assertThat(next(Fixtures.pending().with(p -> p.expiresAt = null), null, NOW, NOW)).isEqualTo(NOW.plusSeconds(15));
  }

  @Test
  void a_hold_is_looked_at_when_it_ends() {
    Payment held = Fixtures.authorized();

    assertThat(next(held, null, NOW, NOW)).isEqualTo(held.holdEndsAt());
    assertThat(next(held, null, NOW, held.holdEndsAt())).isEqualTo(held.holdEndsAt().plusSeconds(30));
    assertThat(next(held.with(p -> p.holdEndsAt = null), null, NOW, NOW)).isEqualTo(NOW.plusSeconds(30));
  }

  @Test
  void an_operation_in_flight_is_decided_one_quiet_minute_after_its_last_call() {
    Operation sentTenSecondsAgo = new Operation(OperationType.REFUND, 100, 1, NOW.minusSeconds(10));
    Operation sentLongAgo = new Operation(OperationType.REFUND, 100, 1, NOW.minusSeconds(60));

    assertThat(next(Fixtures.captured(1_000, 0), sentTenSecondsAgo, NOW, NOW)).isEqualTo(NOW.plusSeconds(50));
    assertThat(next(Fixtures.captured(1_000, 0), sentLongAgo, NOW, NOW)).isEqualTo(NOW.plusSeconds(30));
  }

  @Test
  void a_waiting_follow_up_is_started_now_but_not_before_the_operation_in_flight_ends() {
    Payment late = Fixtures.captured(1_000, 0).with(p -> p.pendingFollowUp = FollowUp.AUTO_REFUND);
    Operation inFlight = new Operation(OperationType.REFUND, 100, 1, NOW);

    assertThat(next(late, null, NOW, NOW)).isEqualTo(NOW);
    assertThat(next(late, inFlight, NOW, NOW)).isEqualTo(NOW.plus(QUIET));
  }

  @ParameterizedTest
  @EnumSource(value = PaymentStatus.class, names = {"FAILED", "CANCELED", "EXPIRED"})
  void a_dead_payment_with_nothing_waiting_is_not_looked_at(PaymentStatus status) {
    assertThat(next(Fixtures.dead(status, GatewayStatus.INITIATED), null, NOW, NOW)).isNull();
  }

  @Test
  void a_captured_payment_with_nothing_waiting_is_not_looked_at() {
    assertThat(next(Fixtures.captured(1_000, 0), null, NOW, NOW)).isNull();
  }

  @ParameterizedTest(name = "failure {0} -> {1} s")
  @CsvSource({"0,30", "1,30", "2,60", "3,120", "4,240", "5,480", "6,600", "50,600"})
  void after_a_failed_fetch_the_wait_doubles_up_to_ten_minutes(int failures, long seconds) {
    assertThat(SyncSchedule.afterFailedFetch(failures, NOW)).isEqualTo(NOW.plusSeconds(seconds));
  }
}
