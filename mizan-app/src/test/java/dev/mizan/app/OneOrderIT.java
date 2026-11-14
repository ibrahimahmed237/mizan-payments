package dev.mizan.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.app.support.Api.Answer;
import dev.mizan.app.support.IntegrationTest;
import dev.mizan.core.payment.GatewayStatus;
import dev.mizan.simulator.Call;
import dev.mizan.simulator.Fault;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** One order, one payment that counts: each row of the table. See docs/lld/payments.md. */
class OneOrderIT extends IntegrationTest {

  @Test
  void no_second_payment_while_the_first_ones_result_is_unknown() {
    String order = newOrder();
    world.simulator.plan(order, Call.CREATE, Fault.TIMEOUT_BEFORE);
    Answer first = pay(order, 15_000, "1111", "AUTO");
    assertThat(first.text("pending_reason")).isEqualTo("gateway_unknown");

    Answer second = pay(order, 15_000, "1111", "AUTO");

    assertThat(second.status()).isEqualTo(409);
    assertThat(second.code()).isEqualTo("previous_payment_unresolved");
    assertThat(world.simulator.paymentsOf(order)).isEmpty(); // the second create never reached the gateway
  }

  @Test
  void no_second_payment_for_an_order_that_has_a_hold() {
    String order = newOrder();
    authorized(order, 15_000);

    Answer second = pay(order, 15_000, "1111", "AUTO");

    assertThat(second.status()).isEqualTo(409);
    assertThat(second.code()).isEqualTo("reference_already_paid");
    assertThat(world.simulator.paymentsOf(order)).hasSize(1);
  }

  @Test
  void no_second_payment_for_an_order_that_is_paid_even_partly_refunded() {
    String order = newOrder();
    String paid = captured(order, 15_000);
    api.post("/v1/payments/" + paid + "/refunds", Map.of("amount", 14_999));

    Answer second = pay(order, 15_000, "1111", "AUTO");

    assertThat(second.code()).isEqualTo("reference_already_paid");
  }

  @Test
  void a_new_payment_is_allowed_after_a_full_refund_a_failure_or_a_cancel() {
    String refunded = newOrder();
    api.post("/v1/payments/" + captured(refunded, 15_000) + "/refunds", Map.of());
    String failed = newOrder();
    pay(failed, 15_000, "0002", "AUTO");
    String canceled = newOrder();
    api.post("/v1/payments/" + authorized(canceled, 15_000) + "/cancel", Map.of());

    assertThat(pay(refunded, 15_000, "1111", "AUTO").status()).isEqualTo(201);
    assertThat(pay(failed, 15_000, "1111", "AUTO").status()).isEqualTo(201);
    assertThat(pay(canceled, 15_000, "1111", "AUTO").status()).isEqualTo(201);
  }

  @Test
  void a_second_attempt_is_allowed_while_the_first_waits_for_the_customer() {
    String order = newOrder();
    Answer first = pay(order, 15_000, "3333", "AUTO");
    assertThat(first.text("pending_reason")).isEqualTo("customer_action");

    Answer second = pay(order, 15_000, "3333", "AUTO"); // people do change cards

    assertThat(second.status()).isEqualTo(201);
    assertThat(second.text("id")).isNotEqualTo(first.text("id"));
  }

  @Test
  void the_first_to_take_money_wins_and_the_orders_other_waiting_payments_are_canceled() {
    String order = newOrder();
    Answer waiting = pay(order, 15_000, "3333", "AUTO");
    String first = waiting.text("id");
    String second = pay(order, 15_000, "3333", "AUTO").text("id");

    Answer winner = pay(order, 15_000, "1111", "AUTO"); // a third attempt, with a card that pays at once

    assertThat(winner.text("status")).isEqualTo("CAPTURED");
    assertThat(payment(first).text("status")).isEqualTo("CANCELED");
    assertThat(payment(second).text("status")).isEqualTo("CANCELED");
    assertThat(payment(first).json().path("next_action").isNull()).isTrue();
    assertThat(events(first)).containsExactly("payment.canceled");
    assertThat(events(second)).containsExactly("payment.canceled");
    assertThat(payment(first).number("version")).isEqualTo(waiting.number("version") + 1); // a change the store can order
    // Payments of other orders are not touched.
    String other = pay(newOrder(), 15_000, "3333", "AUTO").text("id");
    assertThat(payment(other).text("status")).isEqualTo("PENDING");
  }

  @Test
  void a_hold_wins_too() {
    String order = newOrder();
    String waiting = pay(order, 15_000, "3333", "AUTO").text("id");

    authorized(order, 15_000);

    assertThat(payment(waiting).text("status")).isEqualTo("CANCELED");
  }

  @Test
  void two_tabs_the_customer_pays_both_and_the_loser_is_a_late_capture_that_is_returned() {
    String order = newOrder();
    String first = pay(order, 15_000, "3333", "AUTO").text("id");
    String second = pay(order, 15_000, "3333", "AUTO").text("id");

    // The customer approves both at the bank. Mizan hears of the first, then of the second.
    world.simulator.customerApproves(first);
    world.simulator.customerApproves(second);
    assertThat(sync(first).text("status")).isEqualTo("CAPTURED");
    Answer loser = sync(second);

    assertThat(payment(first).json().path("late").asBoolean()).isFalse();
    assertThat(loser.text("status")).isEqualTo("CAPTURED"); // the money is real, so it is in the books
    assertThat(loser.json().path("late").asBoolean()).isTrue();
    assertThat(loser.number("refunded_amount")).isEqualTo(15_000); // and it is returned at once
    assertThat(entries(second)).containsExactly("CAPTURE 15000", "REFUND 15000");
    assertThat(events(second)).containsExactly("payment.canceled", "payment.captured", "refund.succeeded");
    assertThat(operations(second)).containsExactly("CREATE SUCCEEDED API 15000", "REFUND SUCCEEDED SYSTEM 15000");
    assertThat(atGateway(second).refunded()).isEqualTo(15_000);
    assertThat(atGateway(first).refunded()).isZero();
    assertThat(payment(first).number("refunded_amount")).isZero();
  }

  @Test
  void the_loser_is_returned_even_when_late_captures_are_kept() {
    // The policy "keep" is for an order that has no other money. Here the order is paid already, so it cannot be kept.
    // This test runs with the policy "refund"; the rule itself is tested for "keep" in mizan-core (SyncTest).
    String order = newOrder();
    String loser = pay(order, 15_000, "3333", "AUTO").text("id");
    captured(order, 15_000);

    world.simulator.customerApproves(loser);
    sync(loser);

    assertThat(payment(loser).number("refunded_amount")).isEqualTo(15_000);
  }

  @Test
  void a_late_hold_on_a_canceled_payment_is_released() {
    String order = newOrder();
    String loser = api.post("/v1/payments", card(order, 15_000, "3333", "MANUAL")).text("id");
    captured(order, 15_000);

    world.simulator.customerApproves(loser);
    Answer after = sync(loser);

    assertThat(after.text("status")).isEqualTo("CANCELED"); // a hold is not money: the payment stays ended
    assertThat(atGateway(loser).status()).isEqualTo(GatewayStatus.VOIDED); // and the hold is released at once
    assertThat(operations(loser)).containsExactly("CREATE SUCCEEDED API 15000", "CANCEL SUCCEEDED SYSTEM 0");
    assertThat(entries(loser)).isEmpty();
    assertThat(events(loser)).containsExactly("payment.canceled"); // the store was told once; nothing new for it
  }

  @Test
  void a_payment_whose_create_is_unknown_is_canceled_by_a_winner_and_its_operation_stays_open() {
    // The first payment waits for the customer. The second attempt's create gets no answer. Then the customer pays the first.
    String order = newOrder();
    String first = pay(order, 15_000, "3333", "AUTO").text("id");
    world.simulator.plan(order, Call.CREATE, Fault.TIMEOUT_BEFORE);
    String unknown = pay(order, 15_000, "1111", "AUTO").text("id");

    world.simulator.customerApproves(first);
    sync(first);

    assertThat(payment(unknown).text("status")).isEqualTo("CANCELED");
    assertThat(operations(unknown)).containsExactly("CREATE IN_FLIGHT API 15000"); // its result is still worth knowing
  }
}
