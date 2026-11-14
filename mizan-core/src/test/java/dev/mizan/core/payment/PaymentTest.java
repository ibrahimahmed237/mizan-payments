package dev.mizan.core.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mizan.core.Fixtures;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class PaymentTest {

  @Test
  void a_new_payment_is_pending_with_no_money() {
    Payment payment = Fixtures.fresh();

    assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(payment.captured()).isZero();
    assertThat(payment.gatewayStatus()).isNull();
    assertThat(payment.followUpsStarted()).isEmpty();
  }

  @Test
  void the_limits_cannot_be_broken() {
    Payment captured = Fixtures.captured(8_000, 3_000);

    assertThatThrownBy(() -> captured.with(p -> p.refunded = 8_001)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> captured.with(p -> p.captured = Fixtures.AMOUNT + 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> captured.with(p -> p.refunded = -1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> captured.with(p -> p.amount = 0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void captured_means_money_and_money_means_captured() {
    assertThatThrownBy(() -> Fixtures.pending().with(p -> p.captured = 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Fixtures.pending().with(p -> p.status = PaymentStatus.CAPTURED))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refundable_is_what_is_captured_and_not_refunded() {
    assertThat(Fixtures.captured(8_000, 3_000).refundable()).isEqualTo(5_000);
    assertThat(Fixtures.pending().refundable()).isZero();
  }

  @Test
  void a_copy_does_not_change_the_original_and_its_follow_ups_cannot_be_changed_from_outside() {
    Payment original = Fixtures.pending();
    var started = EnumSet.of(FollowUp.AUTO_VOID);

    Payment copy = original.with(p -> p.followUpsStarted = started);
    started.add(FollowUp.AUTO_REFUND);

    assertThat(original.followUpsStarted()).isEmpty();
    assertThat(copy.followUpsStarted()).containsExactly(FollowUp.AUTO_VOID);
    assertThatThrownBy(() -> copy.followUpsStarted().add(FollowUp.AUTO_CAPTURE)).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void only_failed_canceled_and_expired_are_dead() {
    assertThat(EnumSet.allOf(PaymentStatus.class).stream().filter(PaymentStatus::isDead))
        .containsExactly(PaymentStatus.FAILED, PaymentStatus.CANCELED, PaymentStatus.EXPIRED);
  }

  @Test
  void each_method_has_its_gateway_and_each_gateway_its_abilities() {
    assertThat(Method.CARD.gateway()).isEqualTo(Gateway.CARD_GATEWAY);
    assertThat(Method.APPLE_PAY.gateway()).isEqualTo(Gateway.CARD_GATEWAY);
    assertThat(Method.PAY_LATER.gateway()).isEqualTo(Gateway.PAY_LATER);
    assertThat(Gateway.CARD_GATEWAY.capturesByItself()).isTrue();
    assertThat(Gateway.CARD_GATEWAY.supportsPartialCapture()).isTrue();
    assertThat(Gateway.CARD_GATEWAY.hasRefundKey()).isFalse();
    assertThat(Gateway.PAY_LATER.capturesByItself()).isFalse();
    assertThat(Gateway.PAY_LATER.supportsPartialCapture()).isFalse();
    assertThat(Gateway.PAY_LATER.hasRefundKey()).isTrue();
  }

  @Test
  void a_status_at_the_gateway_only_steps_forward() {
    assertThat(GatewayStatus.NOT_FOUND.step()).isLessThan(GatewayStatus.INITIATED.step());
    assertThat(GatewayStatus.INITIATED.step()).isLessThan(GatewayStatus.AUTHORIZED.step());
    assertThat(GatewayStatus.AUTHORIZED.step()).isLessThan(GatewayStatus.FAILED.step());
    assertThat(GatewayStatus.FAILED.step()).isEqualTo(GatewayStatus.VOIDED.step()).isEqualTo(GatewayStatus.EXPIRED.step());
    assertThat(GatewayStatus.EXPIRED.step()).isLessThan(GatewayStatus.PAID.step());
  }

  @Test
  void an_operation_needs_a_type_a_time_and_one_attempt() {
    assertThatThrownBy(() -> new Operation(OperationType.REFUND, -1, 1, Fixtures.NOW)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Operation(OperationType.REFUND, 1, 0, Fixtures.NOW)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Operation(null, 1, 1, Fixtures.NOW)).isInstanceOf(NullPointerException.class);
    assertThat(FollowUp.AUTO_CAPTURE.operation()).isEqualTo(OperationType.CAPTURE);
    assertThat(FollowUp.AUTO_VOID.operation()).isEqualTo(OperationType.CANCEL);
    assertThat(FollowUp.AUTO_REFUND.operation()).isEqualTo(OperationType.REFUND);
  }
}
