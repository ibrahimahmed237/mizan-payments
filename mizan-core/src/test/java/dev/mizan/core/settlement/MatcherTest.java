package dev.mizan.core.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.core.fees.FeeRule;
import java.time.Instant;
import java.util.OptionalLong;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MatcherTest {

  private static final Instant AT = Instant.parse("2026-11-14T09:00:00Z");
  private static final MatchOutcome MATCHED = new MatchOutcome.Matched();
  private static final MatchOutcome NEEDS_SYNC = new MatchOutcome.NeedsSync();

  private static SettlementLine capture(long gross, long fee) {
    return new SettlementLine(1, "txn_1", "pay_1", LineType.CAPTURE, "capture", gross, fee, gross - fee, AT);
  }

  private static SettlementLine refund(long amount) {
    return new SettlementLine(1, "txn_2", "pay_1", LineType.REFUND, "refund", -amount, 0, -amount, AT);
  }

  private static MatchOutcome mismatch(DiscrepancyType type) {
    return new MatchOutcome.Mismatch(type);
  }

  @Nested
  class A_capture_line {

    @ParameterizedTest(name = "line {0}, captured {1}, settled {2} -> fits: {3}")
    @CsvSource({"10000,10000,0,true", "4000,10000,0,true", "4000,10000,6000,true", "4001,10000,6000,false", "1,10000,10000,false", "10001,10000,0,false"})
    void fits_what_is_still_unsettled(long line, long captured, long settled, boolean fits) {
      MatchOutcome outcome = Matcher.match(capture(line, 0), new PaymentView(captured, 0, settled, 0), false, false);

      assertThat(outcome).isEqualTo(fits ? MATCHED : NEEDS_SYNC);
    }

    @Test
    void that_does_not_fit_asks_for_a_sync_first() {
      assertThat(Matcher.match(capture(10_000, 0), new PaymentView(0, 0, 0, 0), false, false)).isEqualTo(NEEDS_SYNC);
    }

    @Test
    void for_a_payment_with_nothing_captured_after_the_sync() {
      assertThat(Matcher.match(capture(10_000, 0), new PaymentView(0, 0, 0, 0), false, true))
          .isEqualTo(mismatch(DiscrepancyType.NOT_CAPTURED_HERE));
    }

    @Test
    void for_more_than_is_unsettled_after_the_sync() {
      assertThat(Matcher.match(capture(10_001, 0), new PaymentView(10_000, 0, 0, 0), false, true))
          .isEqualTo(mismatch(DiscrepancyType.AMOUNT_MISMATCH));
      assertThat(Matcher.match(capture(1, 0), new PaymentView(10_000, 0, 10_000, 0), false, true))
          .isEqualTo(mismatch(DiscrepancyType.AMOUNT_MISMATCH));
    }

    @Test
    void that_fits_after_the_sync_matches() {
      assertThat(Matcher.match(capture(10_000, 0), new PaymentView(10_000, 0, 0, 0), false, true)).isEqualTo(MATCHED);
    }
  }

  @Nested
  class A_refund_line {

    @ParameterizedTest(name = "line {0}, refunded {1}, settled {2} -> fits: {3}")
    @CsvSource({"3000,3000,0,true", "1000,3000,0,true", "1000,3000,2000,true", "1001,3000,2000,false", "3001,3000,0,false"})
    void fits_what_is_still_unsettled(long line, long refunded, long settled, boolean fits) {
      MatchOutcome outcome = Matcher.match(refund(line), new PaymentView(10_000, refunded, 10_000, settled), false, false);

      assertThat(outcome).isEqualTo(fits ? MATCHED : NEEDS_SYNC);
    }

    @Test
    void that_still_does_not_fit_after_the_sync_is_an_unknown_refund() {
      assertThat(Matcher.match(refund(3_000), new PaymentView(10_000, 0, 0, 0), false, true))
          .isEqualTo(mismatch(DiscrepancyType.UNKNOWN_REFUND));
    }

    @Test
    void does_not_use_the_unsettled_capture() {
      assertThat(Matcher.match(refund(3_000), new PaymentView(10_000, 0, 0, 0), false, false)).isEqualTo(NEEDS_SYNC);
    }
  }

  @Test
  void a_line_of_another_type_is_never_matched() {
    var chargeback = new SettlementLine(1, "txn_3", "pay_1", LineType.OTHER, "chargeback", -5_000, 0, -5_000, AT);

    assertThat(Matcher.match(chargeback, new PaymentView(10_000, 0, 0, 0), false, false)).isEqualTo(mismatch(DiscrepancyType.UNKNOWN_LINE_TYPE));
    assertThat(Matcher.match(chargeback, null, true, true)).isEqualTo(mismatch(DiscrepancyType.UNKNOWN_LINE_TYPE));
  }

  @Test
  void a_transaction_that_was_settled_before_is_a_duplicate_even_if_it_would_fit() {
    assertThat(Matcher.match(capture(4_000, 0), new PaymentView(10_000, 0, 4_000, 0), true, false))
        .isEqualTo(mismatch(DiscrepancyType.DUPLICATE_SETTLEMENT));
  }

  @Test
  void a_line_for_a_payment_mizan_does_not_have() {
    assertThat(Matcher.match(capture(4_000, 0), null, false, false)).isEqualTo(mismatch(DiscrepancyType.UNKNOWN_PAYMENT));
    assertThat(Matcher.match(refund(4_000), null, false, true)).isEqualTo(mismatch(DiscrepancyType.UNKNOWN_PAYMENT));
  }

  @Nested
  class The_fee {

    private final FeeRule rule = new FeeRule(250, 100, 1500); // 10000 -> 403

    @Test
    void as_expected_raises_nothing() {
      assertThat(Matcher.unexpectedFee(capture(10_000, 403), rule)).isEmpty();
      assertThat(Matcher.unexpectedFee(refund(3_000), rule)).isEmpty();
    }

    @Test
    void of_a_capture_that_differs_reports_what_was_expected() {
      assertThat(Matcher.unexpectedFee(capture(10_000, 500), rule)).isEqualTo(OptionalLong.of(403));
      assertThat(Matcher.unexpectedFee(capture(10_000, 0), rule)).isEqualTo(OptionalLong.of(403));
    }

    @Test
    void on_a_refund_line_is_expected_to_be_zero() {
      var withFee = new SettlementLine(1, "t", "p", LineType.REFUND, "refund", -3_000, 25, -3_025, AT);

      assertThat(Matcher.unexpectedFee(withFee, rule)).isEqualTo(OptionalLong.of(0));
    }
  }

  @Test
  void only_money_discrepancies_hold_money_in_suspense() {
    assertThat(DiscrepancyType.UNKNOWN_PAYMENT.holdsMoneyInSuspense()).isTrue();
    assertThat(DiscrepancyType.NOT_CAPTURED_HERE.holdsMoneyInSuspense()).isTrue();
    assertThat(DiscrepancyType.AMOUNT_MISMATCH.holdsMoneyInSuspense()).isTrue();
    assertThat(DiscrepancyType.UNKNOWN_REFUND.holdsMoneyInSuspense()).isTrue();
    assertThat(DiscrepancyType.DUPLICATE_SETTLEMENT.holdsMoneyInSuspense()).isTrue();
    assertThat(DiscrepancyType.UNKNOWN_LINE_TYPE.holdsMoneyInSuspense()).isTrue();
    assertThat(DiscrepancyType.FEE_MISMATCH.holdsMoneyInSuspense()).isFalse();
    assertThat(DiscrepancyType.MISSING_FROM_SETTLEMENT.holdsMoneyInSuspense()).isFalse();
    assertThat(DiscrepancyType.AMOUNT_CONFLICT.holdsMoneyInSuspense()).isFalse();
    assertThat(DiscrepancyType.UNKNOWN_PAYMENT_EVENT.holdsMoneyInSuspense()).isFalse();
  }
}
