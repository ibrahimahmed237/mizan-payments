package dev.mizan.core.operation;

import static dev.mizan.core.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.core.Fixtures;
import dev.mizan.core.payment.Gateway;
import dev.mizan.core.payment.GatewayStatus;
import dev.mizan.core.payment.Operation;
import dev.mizan.core.payment.OperationType;
import dev.mizan.core.payment.Payment;
import dev.mizan.core.payment.PaymentStatus;
import dev.mizan.core.sync.Decision;
import dev.mizan.core.sync.Effect.Notify;
import dev.mizan.core.sync.Effect.OperationFinished;
import dev.mizan.core.sync.EventType;
import java.time.Duration;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class OperationRulesTest {

  private static final Duration QUIET = Duration.ofMinutes(1);

  private static Operation sentAt(OperationType type, int attempts, long secondsAgo) {
    return new Operation(type, 1_000, attempts, NOW.minusSeconds(secondsAgo));
  }

  @Nested
  class After_a_fetch_showed_nothing {

    @ParameterizedTest
    @EnumSource(OperationType.class)
    void nothing_is_decided_before_one_quiet_minute(OperationType type) {
      assertThat(OperationRules.afterSilence(sentAt(type, 1, 59), Gateway.CARD_GATEWAY, QUIET, NOW)).isEqualTo(Verdict.WAIT);
      assertThat(OperationRules.afterSilence(sentAt(type, 1, 59), Gateway.PAY_LATER, QUIET, NOW)).isEqualTo(Verdict.WAIT);
    }

    @ParameterizedTest(name = "{0} at {1}, attempt {2} -> {3}")
    @CsvSource({
      "CAPTURE, CARD_GATEWAY, 1, SEND_AGAIN",
      "CAPTURE, CARD_GATEWAY, 2, NO_EFFECT",
      "CAPTURE, PAY_LATER,    1, SEND_AGAIN",
      "CANCEL,  CARD_GATEWAY, 1, SEND_AGAIN",
      "CANCEL,  CARD_GATEWAY, 2, NO_EFFECT",
      "CANCEL,  PAY_LATER,    1, SEND_AGAIN",
      "REFUND,  CARD_GATEWAY, 1, NO_EFFECT",
      "REFUND,  PAY_LATER,    1, SEND_AGAIN",
      "REFUND,  PAY_LATER,    2, NO_EFFECT",
      "CREATE,  CARD_GATEWAY, 1, NO_EFFECT",
      "CREATE,  PAY_LATER,    1, NO_EFFECT",
    })
    void after_the_quiet_minute(OperationType type, Gateway gateway, int attempts, Verdict expected) {
      assertThat(OperationRules.afterSilence(sentAt(type, attempts, 60), gateway, QUIET, NOW)).isEqualTo(expected);
    }

    @Test
    void a_refund_is_repeated_only_where_the_gateway_takes_a_refund_key() {
      assertThat(OperationRules.safeToRepeat(OperationType.REFUND, Gateway.CARD_GATEWAY)).isFalse();
      assertThat(OperationRules.safeToRepeat(OperationType.REFUND, Gateway.PAY_LATER)).isTrue();
      assertThat(OperationRules.safeToRepeat(OperationType.CREATE, Gateway.PAY_LATER)).isFalse();
    }
  }

  @Nested
  class A_create_that_ended_without_effect {

    @Test
    void fails_a_pending_payment_as_unreachable() {
      Decision decision = OperationRules.noEffect(Fixtures.fresh(), sentAt(OperationType.CREATE, 1, 60));

      assertThat(decision.payment().status()).isEqualTo(PaymentStatus.FAILED);
      assertThat(decision.payment().failureCode()).isEqualTo("gateway_unreachable");
      assertThat(decision.effects()).containsExactly(new OperationFinished(false, "no_effect"), new Notify(EventType.PAYMENT_FAILED));
    }

    @Test
    void leaves_a_payment_that_was_canceled_meanwhile() {
      Payment canceled = Fixtures.dead(PaymentStatus.CANCELED, null);

      Decision decision = OperationRules.noEffect(canceled, sentAt(OperationType.CREATE, 1, 60));

      assertThat(decision.payment()).isEqualTo(canceled);
      assertThat(decision.effects()).containsExactly(new OperationFinished(false, "no_effect"));
    }
  }

  @Nested
  class Other_operations_that_ended_without_effect {

    @ParameterizedTest
    @EnumSource(value = OperationType.class, names = {"CAPTURE", "CANCEL"})
    void leave_a_held_payment_held(OperationType type) {
      Decision decision = OperationRules.noEffect(Fixtures.authorized(), sentAt(type, 2, 60));

      assertThat(decision.payment()).isEqualTo(Fixtures.authorized());
      assertThat(decision.effects()).containsExactly(new OperationFinished(false, "no_effect"));
    }

    @Test
    void leave_a_captured_payment_as_it_is() {
      Decision decision = OperationRules.noEffect(Fixtures.captured(5_000, 0), sentAt(OperationType.REFUND, 1, 60));

      assertThat(decision.payment().refunded()).isZero();
      assertThat(decision.effects()).containsExactly(new OperationFinished(false, "no_effect"));
    }
  }

  @Nested
  class A_clear_no_from_the_gateway {

    @Test
    void fails_the_payment_of_a_create_with_the_gateways_reason() {
      Decision decision = OperationRules.refused(Fixtures.fresh(), sentAt(OperationType.CREATE, 1, 1), "card_declined", "Declined by the bank");

      assertThat(decision.payment().status()).isEqualTo(PaymentStatus.FAILED);
      assertThat(decision.payment().failureCode()).isEqualTo("card_declined");
      assertThat(decision.payment().failureMessage()).isEqualTo("Declined by the bank");
      assertThat(decision.effects()).containsExactly(new OperationFinished(false, "card_declined"), new Notify(EventType.PAYMENT_FAILED));
    }

    @Test
    void fails_only_the_operation_of_a_refund() {
      Decision decision = OperationRules.refused(Fixtures.captured(5_000, 0), sentAt(OperationType.REFUND, 1, 1), "refund_window_closed", null);

      assertThat(decision.payment()).isEqualTo(Fixtures.captured(5_000, 0));
      assertThat(decision.effects()).containsExactly(new OperationFinished(false, "refund_window_closed"));
    }
  }

  @Test
  void a_pay_later_create_with_a_lost_answer_fails_at_once() {
    Payment payment = Fixtures.fresh(Gateway.PAY_LATER, dev.mizan.core.payment.CaptureMode.AUTO);

    Decision decision = OperationRules.unreachable(payment, sentAt(OperationType.CREATE, 1, 1));

    assertThat(decision.payment().status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(decision.payment().failureCode()).isEqualTo("gateway_unreachable");
    assertThat(decision.payment().gatewayStatus()).isNull();
    assertThat(decision.effects()).containsExactly(new OperationFinished(false, "gateway_unreachable"), new Notify(EventType.PAYMENT_FAILED));
    assertThat(GatewayStatus.NOT_FOUND.step()).isZero();
  }
}
