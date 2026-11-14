package dev.mizan.core.sync;

import static dev.mizan.core.Fixtures.AMOUNT;
import static dev.mizan.core.Fixtures.KEEP_LATE;
import static dev.mizan.core.Fixtures.NOW;
import static dev.mizan.core.Fixtures.REFUND_LATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mizan.core.Fixtures;
import dev.mizan.core.payment.CaptureMode;
import dev.mizan.core.payment.FollowUp;
import dev.mizan.core.payment.Gateway;
import dev.mizan.core.payment.GatewayStatus;
import dev.mizan.core.payment.Operation;
import dev.mizan.core.payment.OperationType;
import dev.mizan.core.payment.Payment;
import dev.mizan.core.payment.PaymentStatus;
import dev.mizan.core.sync.Effect.Anomaly;
import dev.mizan.core.sync.Effect.CancelSiblings;
import dev.mizan.core.sync.Effect.Captured;
import dev.mizan.core.sync.Effect.ExternalRefund;
import dev.mizan.core.sync.Effect.FollowUpRequested;
import dev.mizan.core.sync.Effect.Notify;
import dev.mizan.core.sync.Effect.OperationFinished;
import dev.mizan.core.sync.Effect.Refunded;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class SyncTest {

  private static Decision sync(Payment payment, Snapshot snapshot) {
    return Sync.decide(payment, null, snapshot, REFUND_LATE, NOW);
  }

  private static Decision sync(Payment payment, Operation inFlight, Snapshot snapshot) {
    return Sync.decide(payment, inFlight, snapshot, REFUND_LATE, NOW);
  }

  private static Snapshot status(GatewayStatus status) {
    return Snapshot.of(status, AMOUNT, "SAR");
  }

  private static Snapshot paid(long captured, long refunded) {
    return Snapshot.paid(AMOUNT, "SAR", captured, refunded);
  }

  private static List<EventType> events(Decision decision) {
    return decision.effects(Notify.class).stream().map(Notify::event).toList();
  }

  private static Payment local(String status) {
    return switch (status) {
      case "PENDING" -> Fixtures.pending();
      case "AUTHORIZED" -> Fixtures.authorized();
      default -> Fixtures.dead(PaymentStatus.valueOf(status), GatewayStatus.INITIATED);
    };
  }

  @Nested
  class Status_when_no_money_was_taken {

    @ParameterizedTest(name = "{0} + {1} -> {2}")
    @CsvSource({
      "PENDING,    INITIATED,  PENDING,    ",
      "PENDING,    AUTHORIZED, AUTHORIZED, PAYMENT_AUTHORIZED",
      "PENDING,    FAILED,     FAILED,     PAYMENT_FAILED",
      "PENDING,    VOIDED,     CANCELED,   PAYMENT_CANCELED",
      "PENDING,    EXPIRED,    EXPIRED,    PAYMENT_EXPIRED",
      "AUTHORIZED, INITIATED,  AUTHORIZED, ",
      "AUTHORIZED, AUTHORIZED, AUTHORIZED, ",
      "AUTHORIZED, FAILED,     FAILED,     PAYMENT_FAILED",
      "AUTHORIZED, VOIDED,     CANCELED,   PAYMENT_CANCELED",
      "AUTHORIZED, EXPIRED,    EXPIRED,    PAYMENT_EXPIRED",
      "FAILED,     INITIATED,  FAILED,     ",
      "FAILED,     AUTHORIZED, FAILED,     ",
      "FAILED,     FAILED,     FAILED,     ",
      "FAILED,     VOIDED,     FAILED,     ",
      "FAILED,     EXPIRED,    FAILED,     ",
      "CANCELED,   INITIATED,  CANCELED,   ",
      "CANCELED,   AUTHORIZED, CANCELED,   ",
      "CANCELED,   FAILED,     CANCELED,   ",
      "CANCELED,   VOIDED,     CANCELED,   ",
      "CANCELED,   EXPIRED,    CANCELED,   ",
      "EXPIRED,    INITIATED,  EXPIRED,    ",
      "EXPIRED,    AUTHORIZED, EXPIRED,    ",
      "EXPIRED,    FAILED,     EXPIRED,    ",
      "EXPIRED,    VOIDED,     EXPIRED,    ",
      "EXPIRED,    EXPIRED,    EXPIRED,    ",
    })
    void every_cell_of_the_table(String local, GatewayStatus snapshot, PaymentStatus expected, EventType event) {
      Decision decision = sync(local(local), status(snapshot));

      assertThat(decision.payment().status()).isEqualTo(expected);
      assertThat(events(decision)).isEqualTo(event == null ? List.of() : List.of(event));
      assertThat(decision.has(Captured.class)).isFalse();
      assertThat(decision.has(Anomaly.class)).isFalse();
    }

    @Test
    void a_payment_waiting_for_the_customer_gets_the_page_address() {
      Decision decision = sync(Fixtures.fresh(), status(GatewayStatus.INITIATED).withActionUrl("https://bank.example/new"));

      assertThat(decision.payment().actionUrl()).isEqualTo("https://bank.example/new");
      assertThat(decision.payment().gatewayStatus()).isEqualTo(GatewayStatus.INITIATED);
      assertThat(decision.changedNothing()).isTrue();
    }

    @Test
    void the_same_status_again_can_bring_a_new_page_address() {
      Decision decision = sync(Fixtures.pending(), status(GatewayStatus.INITIATED).withActionUrl("https://bank.example/again"));

      assertThat(decision.payment().actionUrl()).isEqualTo("https://bank.example/again");
    }

    @Test
    void a_hold_sets_its_end_and_cancels_the_orders_other_payments() {
      Decision decision = sync(Fixtures.pending(), status(GatewayStatus.AUTHORIZED));

      assertThat(decision.payment().holdEndsAt()).isEqualTo(NOW.plus(Fixtures.HOLD));
      assertThat(decision.payment().actionUrl()).isNull();
      assertThat(decision.has(CancelSiblings.class)).isTrue();
      assertThat(decision.has(FollowUpRequested.class)).isFalse();
    }

    @Test
    void a_pay_later_hold_is_captured_by_mizan_when_the_store_asked_for_automatic_capture() {
      Decision auto = sync(Fixtures.fresh(Gateway.PAY_LATER, CaptureMode.AUTO), status(GatewayStatus.AUTHORIZED));
      Decision manual = sync(Fixtures.fresh(Gateway.PAY_LATER, CaptureMode.MANUAL), status(GatewayStatus.AUTHORIZED));

      assertThat(auto.payment().pendingFollowUp()).isEqualTo(FollowUp.AUTO_CAPTURE);
      assertThat(auto.effects(FollowUpRequested.class)).containsExactly(new FollowUpRequested(FollowUp.AUTO_CAPTURE));
      assertThat(manual.payment().pendingFollowUp()).isNull();
    }

    @Test
    void a_failure_keeps_the_gateways_reason() {
      Decision withReason = sync(Fixtures.pending(), status(GatewayStatus.FAILED).withFailure("insufficient_funds", "Not enough money"));
      Decision without = sync(Fixtures.pending(), status(GatewayStatus.FAILED));

      assertThat(withReason.payment().failureCode()).isEqualTo("insufficient_funds");
      assertThat(withReason.payment().failureMessage()).isEqualTo("Not enough money");
      assertThat(without.payment().failureCode()).isEqualTo("gateway_declined");
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"FAILED", "CANCELED", "EXPIRED"})
    void a_late_hold_on_a_dead_payment_is_voided_once(PaymentStatus dead) {
      Payment payment = Fixtures.dead(dead, GatewayStatus.INITIATED);

      Decision first = sync(payment, status(GatewayStatus.AUTHORIZED));
      Decision second = sync(first.payment(), status(GatewayStatus.AUTHORIZED));

      assertThat(first.payment().status()).isEqualTo(dead);
      assertThat(first.payment().pendingFollowUp()).isEqualTo(FollowUp.AUTO_VOID);
      assertThat(first.effects()).containsExactly(new FollowUpRequested(FollowUp.AUTO_VOID));
      assertThat(second.changedNothing()).isTrue();
    }

    @Test
    void a_late_hold_is_not_voided_again_when_a_void_already_ran() {
      Payment payment = Fixtures.dead(PaymentStatus.EXPIRED, GatewayStatus.INITIATED)
          .with(p -> p.followUpsStarted = EnumSet.of(FollowUp.AUTO_VOID));

      assertThat(sync(payment, status(GatewayStatus.AUTHORIZED)).changedNothing()).isTrue();
    }

    @Test
    void a_dead_payment_that_was_voided_at_the_gateway_ignores_an_old_hold_record() {
      Payment canceledByVoid = Fixtures.dead(PaymentStatus.CANCELED, GatewayStatus.VOIDED);

      assertThat(sync(canceledByVoid, status(GatewayStatus.AUTHORIZED)).changedNothing()).isTrue();
    }

    @Test
    void the_first_end_wins() {
      Payment failed = sync(Fixtures.pending(), status(GatewayStatus.FAILED)).payment();

      Decision later = sync(failed, status(GatewayStatus.VOIDED));

      assertThat(later.payment().status()).isEqualTo(PaymentStatus.FAILED);
      assertThat(later.payment().gatewayStatus()).isEqualTo(GatewayStatus.FAILED);
      assertThat(later.changedNothing()).isTrue();
    }

    @Test
    void an_end_drops_a_follow_up_that_has_nothing_left_to_do() {
      Payment waitingForCapture = sync(Fixtures.fresh(Gateway.PAY_LATER, CaptureMode.AUTO), status(GatewayStatus.AUTHORIZED)).payment();

      assertThat(sync(waitingForCapture, status(GatewayStatus.VOIDED)).payment().pendingFollowUp()).isNull();
    }

    @Test
    void not_found_changes_nothing() {
      assertThat(sync(Fixtures.pending(), Snapshot.notFound()).changedNothing()).isTrue();
      assertThat(sync(Fixtures.pending(), Snapshot.notFound()).payment()).isEqualTo(Fixtures.pending());
    }

    @Test
    void the_card_is_remembered_from_the_first_record_that_has_it() {
      Payment first = sync(Fixtures.fresh(), status(GatewayStatus.INITIATED).withCard("mada", "3333")).payment();
      Payment second = sync(first, status(GatewayStatus.AUTHORIZED).withCard("visa", "9999")).payment();

      assertThat(second.scheme()).isEqualTo("mada");
      assertThat(second.last4()).isEqualTo("3333");
    }
  }

  @Nested
  class Money_taken {

    @ParameterizedTest
    @CsvSource({"PENDING", "AUTHORIZED"})
    void a_capture_on_a_live_payment(String local) {
      Decision decision = sync(local(local), paid(AMOUNT, 0));

      assertThat(decision.payment().status()).isEqualTo(PaymentStatus.CAPTURED);
      assertThat(decision.payment().captured()).isEqualTo(AMOUNT);
      assertThat(decision.payment().capturedAt()).isEqualTo(NOW);
      assertThat(decision.payment().late()).isFalse();
      assertThat(decision.payment().gatewayStatus()).isEqualTo(GatewayStatus.PAID);
      assertThat(decision.effects()).containsExactly(
          new Captured(AMOUNT), new Notify(EventType.PAYMENT_CAPTURED), new CancelSiblings());
    }

    @Test
    void a_partial_capture() {
      Decision decision = sync(Fixtures.authorized(), paid(6_000, 0));

      assertThat(decision.payment().captured()).isEqualTo(6_000);
      assertThat(decision.effects(Captured.class)).containsExactly(new Captured(6_000));
    }

    @Test
    void a_second_capture_that_mizan_did_not_start_is_recorded_too() {
      Decision decision = sync(Fixtures.captured(6_000, 0), paid(9_000, 0));

      assertThat(decision.payment().captured()).isEqualTo(9_000);
      assertThat(decision.payment().capturedAt()).isEqualTo(NOW.minusSeconds(60));
      assertThat(decision.effects()).containsExactly(new Captured(3_000), new Notify(EventType.PAYMENT_CAPTURED));
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"FAILED", "CANCELED", "EXPIRED"})
    void a_late_capture_is_recorded_then_returned(PaymentStatus dead) {
      Decision decision = sync(Fixtures.dead(dead, GatewayStatus.INITIATED), paid(AMOUNT, 0));

      assertThat(decision.payment().status()).isEqualTo(PaymentStatus.CAPTURED);
      assertThat(decision.payment().late()).isTrue();
      assertThat(decision.payment().pendingFollowUp()).isEqualTo(FollowUp.AUTO_REFUND);
      assertThat(decision.effects()).containsExactly(
          new Captured(AMOUNT), new Notify(EventType.PAYMENT_CAPTURED), new FollowUpRequested(FollowUp.AUTO_REFUND));
    }

    @Test
    void a_late_capture_is_kept_when_the_policy_says_so_and_the_order_has_no_other_money() {
      Decision decision = Sync.decide(Fixtures.dead(PaymentStatus.EXPIRED, GatewayStatus.INITIATED), null, paid(AMOUNT, 0), KEEP_LATE, NOW);

      assertThat(decision.payment().late()).isTrue();
      assertThat(decision.payment().pendingFollowUp()).isNull();
      assertThat(decision.effects()).containsExactly(
          new Captured(AMOUNT), new Notify(EventType.PAYMENT_CAPTURED), new CancelSiblings());
    }

    @Test
    void a_late_capture_is_returned_whatever_the_policy_when_another_payment_of_the_order_has_the_money() {
      var orderIsPaid = new SyncContext(LateCapturePolicy.KEEP, Fixtures.HOLD, true);

      Decision decision = Sync.decide(Fixtures.dead(PaymentStatus.CANCELED, GatewayStatus.INITIATED), null, paid(AMOUNT, 0), orderIsPaid, NOW);

      assertThat(decision.payment().pendingFollowUp()).isEqualTo(FollowUp.AUTO_REFUND);
      assertThat(decision.has(CancelSiblings.class)).isFalse();
    }

    @Test
    void a_late_capture_is_not_returned_twice() {
      Payment refundedOnce = Fixtures.dead(PaymentStatus.EXPIRED, GatewayStatus.INITIATED)
          .with(p -> p.followUpsStarted = EnumSet.of(FollowUp.AUTO_REFUND));

      Decision decision = sync(refundedOnce, paid(AMOUNT, 0));

      assertThat(decision.payment().pendingFollowUp()).isNull();
      assertThat(decision.has(FollowUpRequested.class)).isFalse();
    }

    @Test
    void a_capture_drops_a_waiting_automatic_capture() {
      Payment waiting = sync(Fixtures.fresh(Gateway.PAY_LATER, CaptureMode.AUTO), status(GatewayStatus.AUTHORIZED)).payment();

      assertThat(sync(waiting, paid(AMOUNT, 0)).payment().pendingFollowUp()).isNull();
    }

    @Test
    void capture_and_refund_seen_together_for_the_first_time() {
      Decision decision = sync(Fixtures.pending(), paid(AMOUNT, 4_000));

      assertThat(decision.payment().captured()).isEqualTo(AMOUNT);
      assertThat(decision.payment().refunded()).isEqualTo(4_000);
      assertThat(decision.effects()).containsExactly(
          new Captured(AMOUNT), new Notify(EventType.PAYMENT_CAPTURED), new CancelSiblings(),
          new Refunded(4_000), new ExternalRefund(4_000));
    }
  }

  @Nested
  class Money_given_back {

    @Test
    void a_refund_mizan_started() {
      Decision decision = sync(Fixtures.captured(AMOUNT, 0), Fixtures.inFlight(OperationType.REFUND, 3_000), paid(AMOUNT, 3_000));

      assertThat(decision.payment().refunded()).isEqualTo(3_000);
      assertThat(decision.payment().lastRefundAt()).isEqualTo(NOW);
      assertThat(decision.payment().status()).isEqualTo(PaymentStatus.CAPTURED);
      assertThat(decision.effects()).containsExactly(new Refunded(3_000), new OperationFinished(true, null));
    }

    @Test
    void a_refund_made_outside_mizan() {
      Decision decision = sync(Fixtures.captured(AMOUNT, 1_000), paid(AMOUNT, 3_500));

      assertThat(decision.effects()).containsExactly(new Refunded(2_500), new ExternalRefund(2_500));
    }

    @Test
    void more_than_mizan_asked_for_is_its_refund_plus_an_outside_one() {
      Decision decision = sync(Fixtures.captured(AMOUNT, 0), Fixtures.inFlight(OperationType.REFUND, 3_000), paid(AMOUNT, 5_000));

      assertThat(decision.effects()).containsExactly(
          new Refunded(5_000), new OperationFinished(true, null), new ExternalRefund(2_000));
    }

    @Test
    void less_than_mizan_asked_for_is_an_outside_refund_and_the_operation_stays_in_flight() {
      Decision decision = sync(Fixtures.captured(AMOUNT, 0), Fixtures.inFlight(OperationType.REFUND, 3_000), paid(AMOUNT, 1_000));

      assertThat(decision.effects()).containsExactly(new Refunded(1_000), new ExternalRefund(1_000));
    }

    @Test
    void a_refund_seen_while_another_kind_of_operation_is_in_flight_is_an_outside_refund() {
      Decision decision = sync(Fixtures.captured(AMOUNT, 0), Fixtures.inFlight(OperationType.CANCEL, 0), paid(AMOUNT, 1_000));

      assertThat(decision.effects()).containsExactly(
          new Refunded(1_000), new ExternalRefund(1_000), new OperationFinished(false, "already_captured"));
    }

    @Test
    void a_full_refund_keeps_the_payment_captured() {
      Decision decision = sync(Fixtures.captured(AMOUNT, 0), paid(AMOUNT, AMOUNT));

      assertThat(decision.payment().status()).isEqualTo(PaymentStatus.CAPTURED);
      assertThat(decision.payment().refundable()).isZero();
    }
  }

  @Nested
  class Old_records {

    @Test
    void lower_totals_are_ignored_without_an_alarm() {
      Payment payment = Fixtures.captured(AMOUNT, 4_000);

      Decision decision = sync(payment, paid(AMOUNT, 1_000));

      assertThat(decision.changedNothing()).isTrue();
      assertThat(decision.payment()).isEqualTo(payment);
    }

    @Test
    void a_status_behind_a_captured_payment_is_ignored() {
      Payment payment = Fixtures.captured(AMOUNT, 0);

      for (GatewayStatus old : List.of(GatewayStatus.INITIATED, GatewayStatus.AUTHORIZED, GatewayStatus.FAILED, GatewayStatus.VOIDED, GatewayStatus.EXPIRED)) {
        Decision decision = sync(payment, status(old));
        assertThat(decision.changedNothing()).as(old.name()).isTrue();
        assertThat(decision.payment()).as(old.name()).isEqualTo(payment);
      }
    }

    @Test
    void a_higher_capture_with_a_lower_refund_takes_only_the_capture() {
      Decision decision = sync(Fixtures.captured(6_000, 2_000), paid(9_000, 1_000));

      assertThat(decision.payment().captured()).isEqualTo(9_000);
      assertThat(decision.payment().refunded()).isEqualTo(2_000);
      assertThat(decision.has(Refunded.class)).isFalse();
    }
  }

  @Nested
  class A_record_that_is_not_this_payment {

    @Test
    void another_amount_is_never_accepted_as_paid() {
      Payment payment = Fixtures.pending();

      Decision decision = sync(payment, Snapshot.paid(100, "SAR", 100, 0));

      assertThat(decision.payment()).isEqualTo(payment);
      assertThat(decision.effects()).containsExactly(new Anomaly(AnomalyType.AMOUNT_CONFLICT));
    }

    @Test
    void another_currency_is_never_accepted() {
      Decision decision = sync(Fixtures.pending(), Snapshot.paid(AMOUNT, "USD", AMOUNT, 0));

      assertThat(decision.effects()).containsExactly(new Anomaly(AnomalyType.AMOUNT_CONFLICT));
      assertThat(decision.payment().status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void the_anomaly_does_not_touch_the_operation_or_the_remembered_status() {
      Decision decision = sync(Fixtures.pending(), Fixtures.inFlight(OperationType.CREATE, 0), Snapshot.of(GatewayStatus.FAILED, 1, "SAR"));

      assertThat(decision.has(OperationFinished.class)).isFalse();
      assertThat(decision.payment().gatewayStatus()).isEqualTo(GatewayStatus.INITIATED);
    }
  }

  @Nested
  class The_operation_in_flight {

    @ParameterizedTest
    @EnumSource(value = GatewayStatus.class, names = {"INITIATED", "AUTHORIZED", "FAILED", "VOIDED", "EXPIRED"})
    void a_create_succeeded_when_the_gateway_has_the_payment(GatewayStatus seen) {
      Decision decision = sync(Fixtures.fresh(), Fixtures.inFlight(OperationType.CREATE, 0), status(seen));

      assertThat(decision.effects(OperationFinished.class)).containsExactly(new OperationFinished(true, null));
    }

    @Test
    void a_create_succeeded_when_the_money_was_taken() {
      Decision decision = sync(Fixtures.fresh(), Fixtures.inFlight(OperationType.CREATE, 0), paid(AMOUNT, 0));

      assertThat(decision.effects(OperationFinished.class)).containsExactly(new OperationFinished(true, null));
    }

    @Test
    void a_create_stays_in_flight_when_the_gateway_does_not_know_the_payment() {
      assertThat(sync(Fixtures.fresh(), Fixtures.inFlight(OperationType.CREATE, 0), Snapshot.notFound()).changedNothing()).isTrue();
    }

    @Test
    void a_capture_succeeded_when_the_captured_total_grew() {
      Decision decision = sync(Fixtures.authorized(), Fixtures.inFlight(OperationType.CAPTURE, AMOUNT), paid(AMOUNT, 0));

      assertThat(decision.effects(OperationFinished.class)).containsExactly(new OperationFinished(true, null));
    }

    @ParameterizedTest
    @EnumSource(value = GatewayStatus.class, names = {"VOIDED", "EXPIRED", "FAILED"})
    void a_capture_failed_when_the_hold_is_gone(GatewayStatus gone) {
      Decision decision = sync(Fixtures.authorized(), Fixtures.inFlight(OperationType.CAPTURE, AMOUNT), status(gone));

      assertThat(decision.effects(OperationFinished.class)).containsExactly(new OperationFinished(false, "hold_gone"));
    }

    @Test
    void a_capture_stays_in_flight_while_the_hold_is_there() {
      assertThat(sync(Fixtures.authorized(), Fixtures.inFlight(OperationType.CAPTURE, AMOUNT), status(GatewayStatus.AUTHORIZED)).changedNothing()).isTrue();
    }

    @Test
    void a_cancel_succeeded_when_the_gateway_shows_the_void() {
      Decision decision = sync(Fixtures.authorized(), Fixtures.inFlight(OperationType.CANCEL, 0), status(GatewayStatus.VOIDED));

      assertThat(decision.payment().status()).isEqualTo(PaymentStatus.CANCELED);
      assertThat(decision.effects()).containsExactly(new Notify(EventType.PAYMENT_CANCELED), new OperationFinished(true, null));
    }

    @Test
    void a_cancel_failed_when_the_money_was_taken_meanwhile() {
      Decision decision = sync(Fixtures.authorized(), Fixtures.inFlight(OperationType.CANCEL, 0), paid(AMOUNT, 0));

      assertThat(decision.payment().status()).isEqualTo(PaymentStatus.CAPTURED);
      assertThat(decision.effects(OperationFinished.class)).containsExactly(new OperationFinished(false, "already_captured"));
    }

    @Test
    void a_cancel_stays_in_flight_while_the_hold_is_there() {
      assertThat(sync(Fixtures.authorized(), Fixtures.inFlight(OperationType.CANCEL, 0), status(GatewayStatus.AUTHORIZED)).changedNothing()).isTrue();
    }

    @Test
    void a_refund_stays_in_flight_while_the_refunded_total_has_not_moved() {
      assertThat(sync(Fixtures.captured(AMOUNT, 0), Fixtures.inFlight(OperationType.REFUND, 1_000), paid(AMOUNT, 0)).changedNothing()).isTrue();
    }
  }

  @Nested
  class The_snapshot_itself {

    @Test
    void refuses_totals_that_cannot_be_true() {
      assertThatThrownBy(() -> Snapshot.paid(100, "SAR", 101, 0)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> Snapshot.paid(100, "SAR", 50, 51)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> Snapshot.paid(100, "SAR", 50, -1)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> Snapshot.of(GatewayStatus.AUTHORIZED, 0, "SAR")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void is_paid_exactly_when_something_was_captured() {
      assertThatThrownBy(() -> Snapshot.paid(100, "SAR", 0, 0)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new Snapshot(GatewayStatus.AUTHORIZED, 100, "SAR", 100, 0, null, null, null, null, null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void not_found_needs_nothing_else() {
      assertThat(Snapshot.notFound().status()).isEqualTo(GatewayStatus.NOT_FOUND);
    }
  }
}
