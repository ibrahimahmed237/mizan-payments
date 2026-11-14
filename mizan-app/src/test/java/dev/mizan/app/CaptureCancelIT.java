package dev.mizan.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.app.support.Api.Answer;
import dev.mizan.app.support.IntegrationTest;
import dev.mizan.core.payment.GatewayStatus;
import dev.mizan.simulator.Call;
import dev.mizan.simulator.Fault;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Capture and cancel: every rule and every refusal. See docs/lld/payments.md. */
class CaptureCancelIT extends IntegrationTest {

  private Answer capture(String id, Object body) {
    return api.post("/v1/payments/" + id + "/capture", body);
  }

  private Answer cancel(String id) {
    return api.post("/v1/payments/" + id + "/cancel", null);
  }

  private static void refused(Answer answer, int status, String code) {
    assertThat(answer.status()).as(answer.raw().body()).isEqualTo(status);
    assertThat(answer.code()).isEqualTo(code);
  }

  // ---------------------------------------------------------------- capture

  @Test
  void a_capture_without_an_amount_takes_the_whole_hold() {
    String id = authorized(newOrder(), 15_000);

    Answer captured = capture(id, null);

    assertThat(captured.status()).isEqualTo(201);
    assertThat(captured.text("type")).isEqualTo("CAPTURE");
    assertThat(captured.text("status")).isEqualTo("SUCCEEDED");
    assertThat(captured.text("origin")).isEqualTo("API");
    assertThat(captured.number("amount")).isEqualTo(15_000);
    assertThat(captured.text("payment_id")).isEqualTo(id);
    assertThat(captured.json().path("failure").isNull()).isTrue();
    assertThat(payment(id).text("status")).isEqualTo("CAPTURED");
    assertThat(payment(id).number("captured_amount")).isEqualTo(15_000);
    assertThat(atGateway(id).captured()).isEqualTo(15_000);
    assertThat(entries(id)).containsExactly("CAPTURE 15000");
    assertThat(events(id)).containsExactly("payment.authorized", "payment.captured");
    assertThat(operations(id)).containsExactly("CREATE SUCCEEDED API 15000", "CAPTURE SUCCEEDED API 15000");
  }

  @Test
  void a_part_capture_takes_the_part_and_releases_the_rest() {
    String id = authorized(newOrder(), 15_000);

    Answer captured = capture(id, Map.of("amount", 10_000));

    assertThat(captured.number("amount")).isEqualTo(10_000);
    assertThat(payment(id).text("status")).isEqualTo("CAPTURED");
    assertThat(payment(id).number("captured_amount")).isEqualTo(10_000);
    assertThat(entries(id)).containsExactly("CAPTURE 10000");
    refused(capture(id, Map.of("amount", 5_000)), 409, "not_capturable"); // one capture per payment
    assertThat(atGateway(id).captured()).isEqualTo(10_000);
  }

  @Test
  void only_an_authorized_payment_can_be_captured() {
    refused(capture(captured(newOrder(), 15_000), null), 409, "not_capturable");
    refused(capture(pay(newOrder(), 15_000, "3333", "MANUAL").text("id"), null), 409, "not_capturable");
    refused(capture(pay(newOrder(), 15_000, "0002", "MANUAL").text("id"), null), 409, "not_capturable");
    refused(capture("00000000-0000-0000-0000-000000000000", null), 404, "not_found");
  }

  @Test
  void the_amount_is_above_zero_and_at_most_the_payments_amount() {
    String id = authorized(newOrder(), 15_000);

    refused(capture(id, Map.of("amount", 0)), 422, "invalid_amount");
    refused(capture(id, Map.of("amount", -5)), 422, "invalid_amount");
    refused(capture(id, Map.of("amount", 15_001)), 422, "invalid_amount");
    assertThat(payment(id).text("status")).isEqualTo("AUTHORIZED");
    assertThat(operations(id)).containsExactly("CREATE SUCCEEDED API 15000"); // a refused request makes no operation
  }

  @Test
  void a_hold_cannot_be_captured_after_its_time() {
    String id = authorized(newOrder(), 15_000);

    advance(Duration.ofDays(7).minusSeconds(1));
    String other = authorized(newOrder(), 15_000); // made now, to show that the time is the payment's own
    advance(Duration.ofSeconds(1));

    refused(capture(id, null), 409, "hold_expired");
    assertThat(capture(other, null).status()).isEqualTo(201);
    assertThat(atGateway(id).captured()).isZero();
  }

  @Test
  void a_hold_that_the_bank_released_is_refused_by_the_gateway_and_the_capture_fails() {
    String order = newOrder();
    String id = authorized(order, 15_000);
    world.simulator.stick(order, Fault.HOLD_RELEASED); // the record still says "authorized" (assumption A4)

    Answer failed = capture(id, null);

    assertThat(failed.status()).isEqualTo(201);
    assertThat(failed.text("status")).isEqualTo("FAILED");
    assertThat(failed.json().path("failure").path("code").asString()).isEqualTo("gateway_refused");
    assertThat(payment(id).text("status")).isEqualTo("AUTHORIZED"); // nothing changed
    assertThat(entries(id)).isEmpty();
    assertThat(events(id)).containsExactly("payment.authorized", "capture.failed");
  }

  @ParameterizedTest
  @EnumSource(value = Fault.class, names = {"LOST_ANSWER", "ERROR_500_AFTER", "GARBAGE"})
  void a_capture_whose_answer_is_lost_is_found_by_a_fetch(Fault fault) {
    String order = newOrder();
    String id = authorized(order, 15_000);
    world.simulator.plan(order, Call.CAPTURE, fault);

    Answer captured = capture(id, null);

    assertThat(captured.status()).isEqualTo(201);
    assertThat(captured.text("status")).isEqualTo("SUCCEEDED");
    assertThat(payment(id).text("status")).isEqualTo("CAPTURED");
    assertThat(atGateway(id).charges()).isEqualTo(1);
    assertThat(entries(id)).containsExactly("CAPTURE 15000");
  }

  @ParameterizedTest
  @EnumSource(value = Fault.class, names = {"TIMEOUT_BEFORE", "ERROR_500"})
  void a_capture_with_no_answer_and_no_change_stays_pending_and_blocks_other_operations(Fault fault) {
    String order = newOrder();
    String id = authorized(order, 15_000);
    world.simulator.plan(order, Call.CAPTURE, fault);

    Answer pending = capture(id, null);

    assertThat(pending.status()).isEqualTo(202); // sent; the result is not known yet
    assertThat(pending.text("status")).isEqualTo("PENDING");
    assertThat(payment(id).text("status")).isEqualTo("AUTHORIZED");
    assertThat(operations(id)).containsExactly("CREATE SUCCEEDED API 15000", "CAPTURE IN_FLIGHT API 15000");
    assertThat(time(id, "next_sync_at")).isEqualTo(now().plusSeconds(60)); // the worker decides after one quiet minute
    // Rule R4: one operation in flight per payment.
    refused(capture(id, null), 409, "operation_in_progress");
    refused(cancel(id), 409, "operation_in_progress");
    assertThat(atGateway(id).captured()).isZero();
  }

  @Test
  void pay_later_captures_the_whole_amount_only() {
    String order = newOrder();
    String id = api.post("/v1/payments", payLater(order, 15_000, "MANUAL")).text("id");
    world.simulator.customerApproves(gatewayId(id));
    assertThat(sync(id).text("status")).isEqualTo("AUTHORIZED");

    refused(capture(id, Map.of("amount", 10_000)), 422, "partial_capture_not_supported");
    Answer captured = capture(id, null);

    assertThat(captured.text("status")).isEqualTo("SUCCEEDED");
    assertThat(payment(id).text("status")).isEqualTo("CAPTURED");
    assertThat(atGateway(gatewayId(id)).captured()).isEqualTo(15_000);
    assertThat(entries(id)).containsExactly("CAPTURE 15000");
  }

  @Test
  void a_pay_later_payment_with_automatic_capture_asks_for_the_capture_when_it_is_approved() {
    String id = api.post("/v1/payments", payLater(newOrder(), 15_000, "AUTO")).text("id");
    world.simulator.customerApproves(gatewayId(id));

    Answer approved = sync(id);

    // The provider does not capture by itself. Mizan sends the capture as a follow-up, in the same step.
    assertThat(approved.text("status")).isEqualTo("CAPTURED");
    assertThat(atGateway(gatewayId(id)).captured()).isEqualTo(15_000);
    assertThat(operations(id)).containsExactly("CREATE SUCCEEDED API 15000", "CAPTURE SUCCEEDED SYSTEM 15000");
    assertThat(events(id)).containsExactly("payment.authorized", "payment.captured");
    assertThat(column(id, "follow_up")).isNull();
  }

  // ---------------------------------------------------------------- cancel

  @Test
  void canceling_a_hold_voids_it_at_the_gateway() {
    String id = authorized(newOrder(), 15_000);

    Answer canceled = cancel(id);

    assertThat(canceled.status()).isEqualTo(201);
    assertThat(canceled.text("type")).isEqualTo("CANCEL");
    assertThat(canceled.text("status")).isEqualTo("SUCCEEDED");
    assertThat(payment(id).text("status")).isEqualTo("CANCELED");
    assertThat(atGateway(id).status()).isEqualTo(GatewayStatus.VOIDED);
    assertThat(entries(id)).isEmpty();
    assertThat(events(id)).containsExactly("payment.authorized", "payment.canceled");
    assertThat(column(id, "next_sync_at")).isNull();
  }

  @Test
  void canceling_a_payment_that_waits_for_the_customer_needs_no_call_to_the_gateway() {
    String id = pay(newOrder(), 15_000, "3333", "AUTO").text("id");

    Answer canceled = cancel(id);

    assertThat(canceled.status()).isEqualTo(201);
    assertThat(canceled.text("status")).isEqualTo("SUCCEEDED");
    assertThat(payment(id).text("status")).isEqualTo("CANCELED");
    assertThat(payment(id).json().path("next_action").isNull()).isTrue();
    assertThat(atGateway(id).status()).isEqualTo(GatewayStatus.INITIATED); // the gateway was not told
    assertThat(operations(id)).containsExactly("CREATE SUCCEEDED API 15000", "CANCEL SUCCEEDED API 0");
    assertThat(events(id)).containsExactly("payment.canceled");
  }

  @Test
  void a_customer_who_pays_after_such_a_cancel_is_a_late_capture() {
    String id = pay(newOrder(), 15_000, "3333", "AUTO").text("id");
    cancel(id);

    world.simulator.customerApproves(id);
    Answer late = sync(id);

    // The money is real, so it is recorded. Then it is returned: the store was told the payment was canceled.
    assertThat(late.text("status")).isEqualTo("CAPTURED");
    assertThat(late.json().path("late").asBoolean()).isTrue();
    assertThat(late.number("refunded_amount")).isEqualTo(15_000);
    assertThat(entries(id)).containsExactly("CAPTURE 15000", "REFUND 15000");
    assertThat(events(id)).containsExactly("payment.canceled", "payment.captured", "refund.succeeded");
    assertThat(atGateway(id).refunded()).isEqualTo(15_000);
  }

  @Test
  void canceling_a_waiting_payment_first_looks_at_the_gateway_and_finds_a_customer_who_just_paid() {
    String id = pay(newOrder(), 15_000, "3333", "AUTO").text("id");
    world.simulator.customerApproves(id); // paid, and Mizan has not heard yet

    Answer answer = cancel(id);

    refused(answer, 409, "already_captured");
    assertThat(payment(id).text("status")).isEqualTo("CAPTURED");
    assertThat(payment(id).json().path("late").asBoolean()).isFalse(); // not late: it was never canceled
  }

  @Test
  void a_waiting_payment_is_not_canceled_when_the_gateway_cannot_be_asked() {
    String order = newOrder();
    String id = pay(order, 15_000, "3333", "AUTO").text("id");
    world.simulator.fetchDown(order, 1);

    Answer answer = cancel(id);

    // "The customer has not paid" must be the gateway's word, just now. Without it, nothing is changed.
    refused(answer, 503, "gateway_unavailable");
    assertThat(payment(id).text("status")).isEqualTo("PENDING");
    assertThat(cancel(id).status()).isEqualTo(201); // the gateway is back
  }

  @Test
  void a_payment_whose_create_is_unknown_cannot_be_canceled_yet() {
    String order = newOrder();
    world.simulator.plan(order, Call.CREATE, Fault.TIMEOUT_BEFORE);
    String id = pay(order, 15_000, "1111", "AUTO").text("id");

    refused(cancel(id), 409, "operation_in_progress");
  }

  @Test
  void a_captured_payment_needs_a_refund_and_an_ended_payment_cannot_be_canceled() {
    String failed = pay(newOrder(), 15_000, "0002", "AUTO").text("id");
    String canceled = authorized(newOrder(), 15_000);
    cancel(canceled);

    refused(cancel(captured(newOrder(), 15_000)), 409, "already_captured");
    refused(cancel(failed), 409, "not_cancelable");
    refused(cancel(canceled), 409, "not_cancelable");
    refused(cancel("00000000-0000-0000-0000-000000000000"), 404, "not_found");
  }

  @Test
  void a_cancel_whose_answer_is_lost_is_found_by_a_fetch() {
    String order = newOrder();
    String id = authorized(order, 15_000);
    world.simulator.plan(order, Call.VOID, Fault.LOST_ANSWER);

    Answer canceled = cancel(id);

    assertThat(canceled.text("status")).isEqualTo("SUCCEEDED");
    assertThat(payment(id).text("status")).isEqualTo("CANCELED");
  }

  @Test
  void a_cancel_with_no_answer_and_no_change_stays_pending() {
    String order = newOrder();
    String id = authorized(order, 15_000);
    world.simulator.plan(order, Call.VOID, Fault.TIMEOUT_BEFORE);

    Answer pending = cancel(id);

    assertThat(pending.status()).isEqualTo(202);
    assertThat(payment(id).text("status")).isEqualTo("AUTHORIZED");
    assertThat(atGateway(id).status()).isEqualTo(GatewayStatus.AUTHORIZED);
  }

  @Test
  void a_cancel_that_meets_a_capture_made_at_the_gateway_fails_and_the_money_is_recorded() {
    String id = authorized(newOrder(), 15_000);
    world.simulator.cardCapture(id, null); // captured in the gateway's dashboard, outside Mizan

    Answer answer = cancel(id);

    // The fresh look before the cancel sees the capture: the rules then say "use a refund".
    refused(answer, 409, "already_captured");
    assertThat(payment(id).text("status")).isEqualTo("CAPTURED");
    assertThat(entries(id)).containsExactly("CAPTURE 15000");
  }
}
