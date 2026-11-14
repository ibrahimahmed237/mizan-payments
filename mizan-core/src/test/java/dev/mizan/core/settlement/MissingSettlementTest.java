package dev.mizan.core.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class MissingSettlementTest {

  private static final Instant NOW = Instant.parse("2026-11-20T09:00:00Z");
  private static final Duration AFTER = Duration.ofDays(3);
  private static final Instant OLD = NOW.minus(AFTER);
  private static final Instant RECENT = NOW.minus(AFTER).plusNanos(1);

  @Test
  void a_capture_that_no_file_paid_out_is_missing_after_the_given_time_not_before() {
    PaymentView unsettled = new PaymentView(15_000, 0, 0, 0);

    assertThat(MissingSettlement.missing(unsettled, OLD, null, AFTER, NOW)).isEqualTo(OptionalLong.of(15_000));
    assertThat(MissingSettlement.missing(unsettled, RECENT, null, AFTER, NOW)).isEmpty();
  }

  @Test
  void only_the_part_that_is_not_settled_is_missing() {
    assertThat(MissingSettlement.missing(new PaymentView(15_000, 0, 10_000, 0), OLD, null, AFTER, NOW)).isEqualTo(OptionalLong.of(5_000));
    assertThat(MissingSettlement.missing(new PaymentView(15_000, 0, 15_000, 0), OLD, null, AFTER, NOW)).isEmpty();
  }

  @Test
  void a_refund_that_no_file_took_back_is_missing_on_its_own_time() {
    PaymentView refundUnsettled = new PaymentView(15_000, 4_000, 15_000, 0);

    assertThat(MissingSettlement.missing(refundUnsettled, OLD.minusSeconds(60), OLD, AFTER, NOW)).isEqualTo(OptionalLong.of(4_000));
    assertThat(MissingSettlement.missing(refundUnsettled, OLD.minusSeconds(60), RECENT, AFTER, NOW)).isEmpty();
  }

  @Test
  void captures_and_refunds_that_are_both_missing_add_up() {
    assertThat(MissingSettlement.missing(new PaymentView(15_000, 4_000, 0, 0), OLD, OLD, AFTER, NOW)).isEqualTo(OptionalLong.of(19_000));
  }

  @Test
  void a_payment_with_no_money_or_no_dates_is_never_missing() {
    assertThat(MissingSettlement.missing(new PaymentView(0, 0, 0, 0), OLD, OLD, AFTER, NOW)).isEmpty();
    assertThat(MissingSettlement.missing(new PaymentView(15_000, 4_000, 0, 0), null, null, AFTER, NOW)).isEmpty();
  }
}
