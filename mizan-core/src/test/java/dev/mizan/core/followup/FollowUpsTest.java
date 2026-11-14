package dev.mizan.core.followup;

import static dev.mizan.core.Fixtures.AMOUNT;
import static dev.mizan.core.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mizan.core.Fixtures;
import dev.mizan.core.followup.FollowUps.Start;
import dev.mizan.core.payment.FollowUp;
import dev.mizan.core.payment.GatewayStatus;
import dev.mizan.core.payment.OperationType;
import dev.mizan.core.payment.Payment;
import dev.mizan.core.payment.PaymentStatus;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FollowUpsTest {

  private static Payment waiting(Payment payment, FollowUp kind) {
    return payment.with(p -> p.pendingFollowUp = kind);
  }

  @Test
  void a_payment_with_nothing_waiting_cannot_begin_a_follow_up() {
    assertThatThrownBy(() -> FollowUps.begin(Fixtures.authorized(), NOW)).isInstanceOf(IllegalArgumentException.class);
  }

  @Nested
  class Automatic_capture {

    @Test
    void captures_the_whole_amount_of_a_hold() {
      Start start = FollowUps.begin(waiting(Fixtures.authorized(), FollowUp.AUTO_CAPTURE), NOW);

      assertThat(start.operation()).isEqualTo(OperationType.CAPTURE);
      assertThat(start.amount()).isEqualTo(AMOUNT);
      assertThat(start.nothingToSend()).isFalse();
      assertThat(start.payment().pendingFollowUp()).isNull();
      assertThat(start.payment().followUpsStarted()).containsExactly(FollowUp.AUTO_CAPTURE);
    }

    @Test
    void is_dropped_when_the_hold_has_ended_or_the_payment_is_no_longer_authorized() {
      Payment authorized = Fixtures.authorized();
      Start ended = FollowUps.begin(waiting(authorized, FollowUp.AUTO_CAPTURE), authorized.holdEndsAt());
      Start justInTime = FollowUps.begin(waiting(authorized, FollowUp.AUTO_CAPTURE), authorized.holdEndsAt().minusNanos(1));
      Start captured = FollowUps.begin(waiting(Fixtures.captured(AMOUNT, 0), FollowUp.AUTO_CAPTURE), NOW);
      Start dead = FollowUps.begin(waiting(Fixtures.dead(PaymentStatus.CANCELED, GatewayStatus.VOIDED), FollowUp.AUTO_CAPTURE), NOW);

      assertThat(ended.nothingToSend()).isTrue();
      assertThat(justInTime.nothingToSend()).isFalse();
      assertThat(captured.nothingToSend()).isTrue();
      assertThat(dead.nothingToSend()).isTrue();
    }

    @Test
    void a_hold_without_an_end_can_be_captured() {
      Payment noEnd = Fixtures.authorized().with(p -> p.holdEndsAt = null);

      assertThat(FollowUps.begin(waiting(noEnd, FollowUp.AUTO_CAPTURE), NOW).nothingToSend()).isFalse();
    }
  }

  @Nested
  class Return_of_a_late_capture {

    @Test
    void refunds_all_that_is_captured_and_not_refunded() {
      Start start = FollowUps.begin(waiting(Fixtures.captured(AMOUNT, 3_000), FollowUp.AUTO_REFUND), NOW);

      assertThat(start.operation()).isEqualTo(OperationType.REFUND);
      assertThat(start.amount()).isEqualTo(AMOUNT - 3_000);
      assertThat(start.payment().followUpsStarted()).containsExactly(FollowUp.AUTO_REFUND);
    }

    @Test
    void is_dropped_when_nothing_is_left_to_return() {
      Start start = FollowUps.begin(waiting(Fixtures.captured(AMOUNT, AMOUNT), FollowUp.AUTO_REFUND), NOW);

      assertThat(start.nothingToSend()).isTrue();
      assertThat(start.amount()).isZero();
      assertThat(start.payment().pendingFollowUp()).isNull();
      assertThat(start.payment().followUpsStarted()).isEmpty(); // dropped is not started
    }
  }

  @Nested
  class Release_of_a_late_hold {

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"FAILED", "CANCELED", "EXPIRED"})
    void voids_a_hold_that_the_gateway_shows_on_an_ended_payment(PaymentStatus status) {
      Start start = FollowUps.begin(waiting(Fixtures.dead(status, GatewayStatus.AUTHORIZED), FollowUp.AUTO_VOID), NOW);

      assertThat(start.operation()).isEqualTo(OperationType.CANCEL);
      assertThat(start.amount()).isZero();
      assertThat(start.payment().followUpsStarted()).containsExactly(FollowUp.AUTO_VOID);
    }

    @Test
    void is_dropped_when_the_gateway_no_longer_shows_a_hold_or_the_payment_is_alive() {
      Start gone = FollowUps.begin(waiting(Fixtures.dead(PaymentStatus.EXPIRED, GatewayStatus.VOIDED), FollowUp.AUTO_VOID), NOW);
      Start never = FollowUps.begin(waiting(Fixtures.dead(PaymentStatus.EXPIRED, GatewayStatus.INITIATED), FollowUp.AUTO_VOID), NOW);
      Start alive = FollowUps.begin(waiting(Fixtures.authorized(), FollowUp.AUTO_VOID), NOW);

      assertThat(gone.nothingToSend()).isTrue();
      assertThat(never.nothingToSend()).isTrue();
      assertThat(alive.nothingToSend()).isTrue();
    }
  }

  @Test
  void a_follow_up_that_begins_keeps_the_ones_started_before() {
    Payment refundedOnce = Fixtures.dead(PaymentStatus.EXPIRED, GatewayStatus.AUTHORIZED).with(p -> {
      p.followUpsStarted = Set.of(FollowUp.AUTO_REFUND);
      p.pendingFollowUp = FollowUp.AUTO_VOID;
    });

    Start start = FollowUps.begin(refundedOnce, NOW);

    assertThat(start.payment().followUpsStarted()).containsExactlyInAnyOrder(FollowUp.AUTO_REFUND, FollowUp.AUTO_VOID);
  }

  @Test
  void beginning_changes_nothing_else_in_the_payment() {
    Payment before = waiting(Fixtures.captured(AMOUNT, 0), FollowUp.AUTO_REFUND);

    Payment after = FollowUps.begin(before, NOW).payment();

    assertThat(after.with(p -> {
      p.pendingFollowUp = FollowUp.AUTO_REFUND;
      p.followUpsStarted = Set.of();
    })).isEqualTo(before);
  }
}
