package dev.mizan.core.operation;

import static dev.mizan.core.Fixtures.AMOUNT;
import static dev.mizan.core.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.core.Fixtures;
import dev.mizan.core.payment.CaptureMode;
import dev.mizan.core.payment.Gateway;
import dev.mizan.core.payment.GatewayStatus;
import dev.mizan.core.payment.Payment;
import dev.mizan.core.payment.PaymentStatus;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class CommandRulesTest {

  private static String code(Optional<Refusal> refusal) {
    return refusal.map(Refusal::code).orElse(null);
  }

  private static Payment payLaterHold() {
    return Fixtures.fresh(Gateway.PAY_LATER, CaptureMode.MANUAL).with(p -> {
      p.status = PaymentStatus.AUTHORIZED;
      p.gatewayStatus = GatewayStatus.AUTHORIZED;
      p.holdEndsAt = NOW.plusSeconds(3600);
    });
  }

  @Nested
  class Capture {

    @Test
    void a_hold_can_be_captured_in_full_or_in_part() {
      assertThat(CommandRules.capture(Fixtures.authorized(), AMOUNT, false, NOW)).isEmpty();
      assertThat(CommandRules.capture(Fixtures.authorized(), 1, false, NOW)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"PENDING", "FAILED", "CANCELED", "EXPIRED"})
    void only_a_hold_can_be_captured(PaymentStatus status) {
      Payment payment = status == PaymentStatus.PENDING ? Fixtures.pending() : Fixtures.dead(status, GatewayStatus.INITIATED);

      Optional<Refusal> refusal = CommandRules.capture(payment, AMOUNT, false, NOW);

      assertThat(code(refusal)).isEqualTo("not_capturable");
      assertThat(refusal.orElseThrow().conflict()).isTrue();
    }

    @Test
    void a_captured_payment_cannot_be_captured_again() {
      assertThat(code(CommandRules.capture(Fixtures.captured(5_000, 0), 1_000, false, NOW))).isEqualTo("not_capturable");
    }

    @Test
    void not_while_another_operation_is_in_flight() {
      assertThat(code(CommandRules.capture(Fixtures.authorized(), AMOUNT, true, NOW))).isEqualTo("operation_in_progress");
    }

    @Test
    void not_at_or_after_the_end_of_the_hold() {
      Payment held = Fixtures.authorized();

      assertThat(CommandRules.capture(held, AMOUNT, false, held.holdEndsAt().minusMillis(1))).isEmpty();
      assertThat(code(CommandRules.capture(held, AMOUNT, false, held.holdEndsAt()))).isEqualTo("hold_expired");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, AMOUNT + 1})
    void the_amount_is_above_zero_and_at_most_the_payments(long amount) {
      Optional<Refusal> refusal = CommandRules.capture(Fixtures.authorized(), amount, false, NOW);

      assertThat(code(refusal)).isEqualTo("invalid_amount");
      assertThat(refusal.orElseThrow().conflict()).isFalse();
    }

    @Test
    void pay_later_captures_the_full_amount_only() {
      assertThat(CommandRules.capture(payLaterHold(), AMOUNT, false, NOW)).isEmpty();
      assertThat(code(CommandRules.capture(payLaterHold(), AMOUNT - 1, false, NOW))).isEqualTo("partial_capture_not_supported");
    }
  }

  @Nested
  class Cancel {

    @Test
    void a_hold_is_voided_at_the_gateway() {
      assertThat(CommandRules.cancel(Fixtures.authorized(), false)).isEmpty();
      assertThat(CommandRules.cancelKind(Fixtures.authorized())).isEqualTo(CommandRules.Cancel.VOID_AT_GATEWAY);
    }

    @Test
    void a_payment_waiting_for_the_customer_is_canceled_here() {
      assertThat(CommandRules.cancel(Fixtures.pending(), false)).isEmpty();
      assertThat(CommandRules.cancelKind(Fixtures.pending())).isEqualTo(CommandRules.Cancel.CANCEL_HERE);
    }

    @Test
    void not_while_an_operation_is_in_flight() {
      assertThat(code(CommandRules.cancel(Fixtures.pending(), true))).isEqualTo("operation_in_progress");
      assertThat(code(CommandRules.cancel(Fixtures.authorized(), true))).isEqualTo("operation_in_progress");
    }

    @Test
    void a_captured_payment_needs_a_refund() {
      assertThat(code(CommandRules.cancel(Fixtures.captured(AMOUNT, 0), false))).isEqualTo("already_captured");
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"FAILED", "CANCELED", "EXPIRED"})
    void an_ended_payment_cannot_be_canceled(PaymentStatus status) {
      assertThat(code(CommandRules.cancel(Fixtures.dead(status, GatewayStatus.INITIATED), false))).isEqualTo("not_cancelable");
    }
  }

  @Nested
  class Refund {

    @Test
    void up_to_what_is_captured_and_not_refunded() {
      Payment payment = Fixtures.captured(8_000, 3_000);

      assertThat(CommandRules.refund(payment, 5_000, false)).isEmpty();
      assertThat(CommandRules.refund(payment, 1, false)).isEmpty();
      assertThat(code(CommandRules.refund(payment, 5_001, false))).isEqualTo("refund_too_large");
      assertThat(CommandRules.refund(payment, 5_001, false).orElseThrow().message()).contains("5000");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -5})
    void the_amount_is_above_zero(long amount) {
      assertThat(code(CommandRules.refund(Fixtures.captured(8_000, 0), amount, false))).isEqualTo("refund_too_large");
    }

    @Test
    void a_fully_refunded_payment_has_nothing_left() {
      assertThat(code(CommandRules.refund(Fixtures.captured(8_000, 8_000), 1, false))).isEqualTo("refund_too_large");
    }

    @Test
    void only_a_captured_payment_can_be_refunded() {
      assertThat(code(CommandRules.refund(Fixtures.authorized(), 1, false))).isEqualTo("not_refundable");
      assertThat(code(CommandRules.refund(Fixtures.pending(), 1, false))).isEqualTo("not_refundable");
      assertThat(code(CommandRules.refund(Fixtures.dead(PaymentStatus.FAILED, GatewayStatus.FAILED), 1, false))).isEqualTo("not_refundable");
    }

    @Test
    void not_while_another_operation_is_in_flight() {
      assertThat(code(CommandRules.refund(Fixtures.captured(8_000, 0), 1, true))).isEqualTo("operation_in_progress");
    }
  }

  @Nested
  class One_order_one_payment {

    @Test
    void a_first_payment_or_a_second_try_after_a_dead_one_is_allowed() {
      assertThat(CommandRules.newPaymentForOrder(false, false)).isEmpty();
    }

    @Test
    void no_second_payment_while_the_first_result_is_unknown() {
      assertThat(code(CommandRules.newPaymentForOrder(true, false))).isEqualTo("previous_payment_unresolved");
    }

    @Test
    void no_second_payment_for_a_paid_order() {
      assertThat(code(CommandRules.newPaymentForOrder(false, true))).isEqualTo("reference_already_paid");
      assertThat(code(CommandRules.newPaymentForOrder(true, true))).isEqualTo("reference_already_paid");
    }

    @Test
    void a_payment_has_the_orders_money_when_it_holds_it_or_took_it_and_did_not_give_all_back() {
      assertThat(CommandRules.hasMoney(Fixtures.authorized())).isTrue();
      assertThat(CommandRules.hasMoney(Fixtures.captured(8_000, 7_999))).isTrue();
      assertThat(CommandRules.hasMoney(Fixtures.captured(8_000, 8_000))).isFalse();
      assertThat(CommandRules.hasMoney(Fixtures.pending())).isFalse();
      assertThat(CommandRules.hasMoney(Fixtures.dead(PaymentStatus.EXPIRED, GatewayStatus.INITIATED))).isFalse();
    }
  }
}
