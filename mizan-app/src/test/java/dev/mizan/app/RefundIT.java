package dev.mizan.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.app.support.Api.Answer;
import dev.mizan.app.support.Books;
import dev.mizan.app.support.IntegrationTest;
import dev.mizan.simulator.Call;
import dev.mizan.simulator.Fault;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Refunds: every rule, every refusal, and refunds made outside Mizan. See docs/lld/payments.md, "Refund". */
class RefundIT extends IntegrationTest {

  private Answer refund(String id, Object body) {
    return api.post("/v1/payments/" + id + "/refunds", body);
  }

  private static void refused(Answer answer, int status, String code) {
    assertThat(answer.status()).as(answer.raw().body()).isEqualTo(status);
    assertThat(answer.code()).isEqualTo(code);
  }

  @Test
  void a_part_refund_gives_money_back_and_the_payment_stays_captured() {
    String id = captured(newOrder(), 15_000);

    Answer refunded = refund(id, Map.of("amount", 5_000, "reason", "Returned one item"));

    assertThat(refunded.status()).isEqualTo(201);
    assertThat(refunded.text("type")).isEqualTo("REFUND");
    assertThat(refunded.text("status")).isEqualTo("SUCCEEDED");
    assertThat(refunded.number("amount")).isEqualTo(5_000);
    assertThat(refunded.text("reason")).isEqualTo("Returned one item");
    assertThat(refunded.text("origin")).isEqualTo("API");
    Answer payment = payment(id);
    assertThat(payment.text("status")).isEqualTo("CAPTURED");
    assertThat(payment.number("captured_amount")).isEqualTo(15_000);
    assertThat(payment.number("refunded_amount")).isEqualTo(5_000);
    assertThat(atGateway(id).refunded()).isEqualTo(5_000);
    assertThat(entries(id)).containsExactly("CAPTURE 15000", "REFUND 5000");
    assertThat(events(id)).containsExactly("payment.captured", "refund.succeeded");
  }

  @Test
  void refunds_add_up_to_the_captured_amount_and_no_further() {
    String id = captured(newOrder(), 15_000);

    assertThat(refund(id, Map.of("amount", 5_000)).status()).isEqualTo(201);
    assertThat(refund(id, Map.of("amount", 9_999)).status()).isEqualTo(201);
    refused(refund(id, Map.of("amount", 2)), 422, "refund_too_large");
    assertThat(refund(id, Map.of("amount", 1)).status()).isEqualTo(201);
    refused(refund(id, Map.of("amount", 1)), 422, "refund_too_large");

    assertThat(payment(id).number("refunded_amount")).isEqualTo(15_000);
    assertThat(payment(id).text("status")).isEqualTo("CAPTURED"); // fully refunded is still "captured": the totals say the rest
    assertThat(atGateway(id).refunded()).isEqualTo(15_000);
    assertThat(entries(id)).containsExactly("CAPTURE 15000", "REFUND 5000", "REFUND 9999", "REFUND 1");
  }

  @Test
  void two_refunds_of_the_same_amount_are_two_refunds() {
    String id = captured(newOrder(), 15_000);

    refund(id, Map.of("amount", 1_000));
    refund(id, Map.of("amount", 1_000));

    assertThat(payment(id).number("refunded_amount")).isEqualTo(2_000);
    assertThat(atGateway(id).refunded()).isEqualTo(2_000);
    assertThat(entries(id)).containsExactly("CAPTURE 15000", "REFUND 1000", "REFUND 1000");
  }

  @Test
  void a_refund_without_an_amount_gives_back_all_that_is_left() {
    String id = captured(newOrder(), 15_000);
    refund(id, Map.of("amount", 4_000));

    Answer rest = refund(id, null);

    assertThat(rest.number("amount")).isEqualTo(11_000);
    assertThat(payment(id).number("refunded_amount")).isEqualTo(15_000);
    refused(refund(id, null), 422, "refund_too_large"); // nothing is left
  }

  @Test
  void the_amount_is_above_zero_and_at_most_what_is_left() {
    String id = captured(newOrder(), 15_000);

    refused(refund(id, Map.of("amount", 0)), 422, "refund_too_large");
    refused(refund(id, Map.of("amount", -1)), 422, "refund_too_large");
    Answer tooLarge = refund(id, Map.of("amount", 15_001));
    refused(tooLarge, 422, "refund_too_large");
    assertThat(tooLarge.text("title")).contains("15000");
    assertThat(atGateway(id).refunded()).isZero();
    assertThat(operations(id)).containsExactly("CREATE SUCCEEDED API 15000");
  }

  @Test
  void only_a_captured_payment_can_be_refunded() {
    refused(refund(authorized(newOrder(), 15_000), null), 409, "not_refundable");
    refused(refund(pay(newOrder(), 15_000, "3333", "AUTO").text("id"), null), 409, "not_refundable");
    refused(refund(pay(newOrder(), 15_000, "0002", "AUTO").text("id"), null), 409, "not_refundable");
    refused(refund("00000000-0000-0000-0000-000000000000", null), 404, "not_found");
  }

  @Test
  void a_part_capture_can_be_refunded_up_to_what_was_captured() {
    String id = authorized(newOrder(), 15_000);
    api.post("/v1/payments/" + id + "/capture", Map.of("amount", 10_000));

    refused(refund(id, Map.of("amount", 10_001)), 422, "refund_too_large");
    assertThat(refund(id, null).number("amount")).isEqualTo(10_000);
  }

  @Test
  void a_refund_made_in_the_gateways_dashboard_is_recorded_and_counted() {
    String id = captured(newOrder(), 15_000);
    world.simulator.externalRefund(id, 6_000);

    // The store asks for a full refund. Mizan looks at the gateway first, sees the 6,000, and refuses the 15,000.
    Answer tooLarge = refund(id, Map.of("amount", 15_000));

    refused(tooLarge, 422, "refund_too_large");
    assertThat(tooLarge.text("title")).contains("9000");
    assertThat(payment(id).number("refunded_amount")).isEqualTo(6_000);
    assertThat(operations(id)).containsExactly("CREATE SUCCEEDED API 15000", "REFUND SUCCEEDED GATEWAY 6000");
    assertThat(entries(id)).containsExactly("CAPTURE 15000", "REFUND 6000");
    assertThat(events(id)).containsExactly("payment.captured", "refund.succeeded");
    // What is left can still be refunded.
    assertThat(refund(id, null).number("amount")).isEqualTo(9_000);
    assertThat(atGateway(id).refunded()).isEqualTo(15_000);
  }

  @Test
  void a_sync_alone_also_records_a_refund_made_outside() {
    String id = captured(newOrder(), 15_000);
    world.simulator.externalRefund(id, 2_500);

    Answer synced = sync(id);

    assertThat(synced.number("refunded_amount")).isEqualTo(2_500);
    assertThat(api.get("/v1/payments/" + id + "/refunds").json().path("data").get(0).path("origin").asString()).isEqualTo("GATEWAY");
    // Looking again changes nothing (property P1).
    sync(id);
    assertThat(entries(id)).containsExactly("CAPTURE 15000", "REFUND 2500");
    assertThat(events(id)).containsExactly("payment.captured", "refund.succeeded");
  }

  @Test
  void a_void_after_capture_made_at_the_gateway_is_a_full_refund_in_the_books() {
    String id = captured(newOrder(), 15_000);
    world.simulator.cardVoid(id);

    Answer synced = sync(id);

    assertThat(synced.text("status")).isEqualTo("CAPTURED");
    assertThat(synced.number("refunded_amount")).isEqualTo(15_000);
    assertThat(entries(id)).containsExactly("CAPTURE 15000", "REFUND 15000");
  }

  @ParameterizedTest
  @EnumSource(value = Fault.class, names = {"LOST_ANSWER", "ERROR_500_AFTER", "GARBAGE"})
  void a_refund_whose_answer_is_lost_is_found_by_a_fetch(Fault fault) {
    String order = newOrder();
    String id = captured(order, 15_000);
    world.simulator.plan(order, Call.REFUND, fault);

    Answer refunded = refund(id, Map.of("amount", 5_000));

    assertThat(refunded.status()).isEqualTo(201);
    assertThat(refunded.text("status")).isEqualTo("SUCCEEDED");
    assertThat(atGateway(id).refunded()).isEqualTo(5_000); // once
    assertThat(payment(id).number("refunded_amount")).isEqualTo(5_000);
    assertThat(entries(id)).containsExactly("CAPTURE 15000", "REFUND 5000");
  }

  @ParameterizedTest
  @EnumSource(value = Fault.class, names = {"TIMEOUT_BEFORE", "ERROR_500"})
  void a_refund_with_no_answer_and_no_change_is_answered_as_pending(Fault fault) {
    String order = newOrder();
    String id = captured(order, 15_000);
    world.simulator.plan(order, Call.REFUND, fault);

    Answer pending = refund(id, Map.of("amount", 5_000));

    assertThat(pending.status()).isEqualTo(202);
    assertThat(pending.text("status")).isEqualTo("PENDING");
    assertThat(payment(id).number("refunded_amount")).isZero();
    assertThat(atGateway(id).refunded()).isZero();
    // A second refund must wait for the first: both could not be checked against the same total.
    refused(refund(id, Map.of("amount", 5_000)), 409, "operation_in_progress");
    assertThat(api.get("/v1/payments/" + id + "/refunds").json().path("data").get(0).path("status").asString()).isEqualTo("PENDING");
  }

  @Test
  void a_slow_refund_that_lands_after_the_timeout_is_found_by_the_next_look() {
    String order = newOrder();
    String id = captured(order, 15_000);
    world.simulator.plan(order, Call.REFUND, Fault.SLOW_APPLY);
    Answer pending = refund(id, Map.of("amount", 5_000));
    assertThat(pending.status()).isEqualTo(202);

    advance(Duration.ofSeconds(10)); // the gateway does the work a few seconds later
    sync(id);

    assertThat(payment(id).number("refunded_amount")).isEqualTo(5_000);
    assertThat(operations(id)).containsExactly("CREATE SUCCEEDED API 15000", "REFUND SUCCEEDED API 5000");
    assertThat(atGateway(id).refunded()).isEqualTo(5_000);
    assertThat(events(id)).containsExactly("payment.captured", "refund.succeeded");
  }

  @Test
  void a_refund_in_flight_and_a_larger_refund_made_outside_are_told_apart() {
    String order = newOrder();
    String id = captured(order, 15_000);
    world.simulator.plan(order, Call.REFUND, Fault.TIMEOUT_BEFORE);
    refund(id, Map.of("amount", 5_000)); // in flight; the gateway never got it
    world.simulator.externalRefund(id, 7_000); // meanwhile, someone refunds 7,000 in the dashboard

    sync(id);

    // The total grew by 7,000, which covers the 5,000 in flight: that operation is closed, and the other 2,000 is external.
    assertThat(payment(id).number("refunded_amount")).isEqualTo(7_000);
    assertThat(operations(id)).containsExactly(
        "CREATE SUCCEEDED API 15000", "REFUND SUCCEEDED API 5000", "REFUND SUCCEEDED GATEWAY 2000");
    assertThat(entries(id)).containsExactly("CAPTURE 15000", "REFUND 7000");
  }

  @Test
  void a_pay_later_refund_carries_a_key_and_is_done_once() {
    String order = newOrder();
    String id = api.post("/v1/payments", payLater(order, 15_000, "MANUAL")).text("id");
    String atProvider = gatewayId(id);
    world.simulator.customerApproves(atProvider);
    sync(id);
    api.post("/v1/payments/" + id + "/capture", null);

    Answer refunded = refund(id, Map.of("amount", 5_050));
    refund(id, Map.of("amount", 5_050)); // a second refund of the same amount has its own key, so it is done too

    assertThat(refunded.text("status")).isEqualTo("SUCCEEDED");
    assertThat(atGateway(atProvider).refunded()).isEqualTo(10_100);
    assertThat(payment(id).number("refunded_amount")).isEqualTo(10_100);
  }

  @Test
  void the_books_move_by_exactly_the_money_of_each_flow() {
    long salesBefore = Books.balance(jdbc, "sales");
    long receivableBefore = Books.balance(jdbc, "gateway_receivable");

    String id = captured(newOrder(), 15_000);
    refund(id, Map.of("amount", 4_000));
    authorized(newOrder(), 9_000); // a hold moves nothing
    pay(newOrder(), 7_000, "0002", "AUTO"); // a declined card moves nothing

    // Sales is an income account: a credit balance, so it goes down as sales go up.
    assertThat(Books.balance(jdbc, "sales") - salesBefore).isEqualTo(-11_000);
    assertThat(Books.balance(jdbc, "gateway_receivable") - receivableBefore).isEqualTo(11_000);
  }
}
