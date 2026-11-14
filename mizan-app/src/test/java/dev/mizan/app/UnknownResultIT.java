package dev.mizan.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.app.support.Api.Answer;
import dev.mizan.app.support.IntegrationTest;
import dev.mizan.core.payment.GatewayStatus;
import dev.mizan.simulator.Call;
import dev.mizan.simulator.Fault;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * An operation was sent and no answer came. The sync worker waits one quiet minute, then decides: the record moved
 * (done), send it once more (only when that cannot move money twice), or it had no effect.
 * See docs/lld/payments.md, "When the result is not known".
 */
class UnknownResultIT extends IntegrationTest {

  private static final Duration QUIET = Duration.ofMinutes(1);

  /** How many calls were sent for each of a payment's operations, as {@code TYPE calls}. */
  private List<String> calls(String paymentId) {
    return jdbc.sql("SELECT type || ' ' || attempts FROM operations WHERE payment_id = ? ORDER BY seq").param(UUID.fromString(paymentId)).query(String.class).list();
  }

  private List<String> results(String paymentId) {
    return jdbc.sql("SELECT type || ' ' || status || ' ' || coalesce(result_code, '-') FROM operations WHERE payment_id = ? ORDER BY seq")
        .param(UUID.fromString(paymentId)).query(String.class).list();
  }

  // ---------------------------------------------------------------- create: never sent again

  @Test
  void a_create_that_never_reached_the_gateway_fails_after_the_quiet_minute_not_before() {
    String order = newOrder();
    world.simulator.plan(order, Call.CREATE, Fault.TIMEOUT_BEFORE);
    String id = pay(order, 15_000, "1111", "AUTO").text("id");

    advance(QUIET.minusSeconds(1));
    assertThat(sync(id).text("pending_reason")).isEqualTo("gateway_unknown"); // too early: the call may still be on its way

    advance(Duration.ofSeconds(1));
    Answer decided = sync(id);

    assertThat(decided.text("status")).isEqualTo("FAILED");
    assertThat(decided.json().path("failure").path("code").asString()).isEqualTo("gateway_unreachable");
    assertThat(results(id)).containsExactly("CREATE FAILED no_effect");
    assertThat(calls(id)).containsExactly("CREATE 1"); // a create is never sent again: its token is gone
    assertThat(events(id)).containsExactly("payment.failed");
    assertThat(world.simulator.paymentsOf(order)).isEmpty();
    assertThat(column(id, "next_sync_at")).isNull();
    // The order is free again: the store may try once more.
    assertThat(pay(order, 15_000, "1111", "AUTO").text("status")).isEqualTo("CAPTURED");
  }

  @Test
  void a_create_that_charges_long_after_mizan_gave_up_is_a_late_capture_and_is_returned() {
    String order = newOrder();
    world.simulator.plan(order, Call.CREATE, Fault.VERY_SLOW_APPLY); // the gateway does the work two minutes later
    String id = pay(order, 15_000, "1111", "AUTO").text("id");

    advance(QUIET);
    assertThat(sync(id).text("status")).isEqualTo("FAILED"); // the store is told: failed
    advance(QUIET); // now the gateway charges the card
    assertThat(atGateway(id).charges()).isEqualTo(1);

    Answer late = sync(id); // a webhook, the worker, or reconciliation brings Mizan here

    // "Failed" was a report. The totals are the truth: the money is recorded, then returned.
    assertThat(late.text("status")).isEqualTo("CAPTURED");
    assertThat(late.json().path("late").asBoolean()).isTrue();
    assertThat(late.number("refunded_amount")).isEqualTo(15_000);
    assertThat(atGateway(id).refunded()).isEqualTo(15_000);
    assertThat(entries(id)).containsExactly("CAPTURE 15000", "REFUND 15000");
    assertThat(events(id)).containsExactly("payment.failed", "payment.captured", "refund.succeeded");
  }

  @Test
  void a_create_without_effect_does_not_bring_back_a_payment_that_was_canceled_meanwhile() {
    String order = newOrder();
    String first = pay(order, 15_000, "3333", "AUTO").text("id");
    world.simulator.plan(order, Call.CREATE, Fault.TIMEOUT_BEFORE);
    String unknown = pay(order, 15_000, "1111", "AUTO").text("id");
    world.simulator.customerApproves(first);
    sync(first); // the first payment wins the order; the unknown one is canceled

    advance(QUIET);
    Answer decided = sync(unknown);

    assertThat(decided.text("status")).isEqualTo("CANCELED"); // it stays canceled, it does not become failed
    assertThat(results(unknown)).containsExactly("CREATE FAILED no_effect");
    assertThat(events(unknown)).containsExactly("payment.canceled"); // and the store is not told a second ending
  }

  // ---------------------------------------------------------------- capture and cancel: sent once more

  @Test
  void a_capture_that_never_reached_the_gateway_is_sent_once_more_after_the_quiet_minute() {
    String order = newOrder();
    String id = authorized(order, 15_000);
    world.simulator.plan(order, Call.CAPTURE, Fault.TIMEOUT_BEFORE);
    assertThat(api.post("/v1/payments/" + id + "/capture", null).status()).isEqualTo(202);

    advance(QUIET.minusSeconds(1));
    sync(id);
    assertThat(calls(id)).containsExactly("CREATE 1", "CAPTURE 1"); // too early

    advance(Duration.ofSeconds(1));
    Answer decided = sync(id);

    // Safe to repeat: the gateway refuses a second capture of a captured payment.
    assertThat(decided.text("status")).isEqualTo("CAPTURED");
    assertThat(calls(id)).containsExactly("CREATE 1", "CAPTURE 2");
    assertThat(results(id)).containsExactly("CREATE SUCCEEDED -", "CAPTURE SUCCEEDED -");
    assertThat(atGateway(id).captured()).isEqualTo(15_000);
    assertThat(entries(id)).containsExactly("CAPTURE 15000");
  }

  @Test
  void a_capture_that_fails_to_arrive_twice_ends_without_effect_and_is_not_sent_a_third_time() {
    String order = newOrder();
    String id = authorized(order, 15_000);
    world.simulator.plan(order, Call.CAPTURE, Fault.TIMEOUT_BEFORE);
    world.simulator.plan(order, Call.CAPTURE, Fault.TIMEOUT_BEFORE);
    api.post("/v1/payments/" + id + "/capture", null);

    advance(QUIET);
    sync(id); // sent again; no answer again
    assertThat(calls(id)).containsExactly("CREATE 1", "CAPTURE 2");
    assertThat(payment(id).text("status")).isEqualTo("AUTHORIZED");
    advance(QUIET.minusSeconds(1));
    sync(id);
    assertThat(results(id)).containsExactly("CREATE SUCCEEDED -", "CAPTURE IN_FLIGHT -"); // the second call has its own quiet minute

    advance(Duration.ofSeconds(1));
    Answer decided = sync(id);

    assertThat(decided.text("status")).isEqualTo("AUTHORIZED"); // the hold is still there; the store may try again
    assertThat(results(id)).containsExactly("CREATE SUCCEEDED -", "CAPTURE FAILED no_effect");
    assertThat(calls(id)).containsExactly("CREATE 1", "CAPTURE 2");
    assertThat(events(id)).containsExactly("payment.authorized", "capture.failed");
    assertThat(atGateway(id).captured()).isZero();
    assertThat(api.post("/v1/payments/" + id + "/capture", null).text("status")).isEqualTo("SUCCEEDED");
  }

  @Test
  void a_capture_whose_first_call_lands_after_the_second_was_sent_takes_the_money_once() {
    String order = newOrder();
    String id = authorized(order, 15_000);
    world.simulator.plan(order, Call.CAPTURE, Fault.VERY_SLOW_APPLY); // lands two minutes later
    api.post("/v1/payments/" + id + "/capture", null);

    advance(QUIET);
    assertThat(sync(id).text("status")).isEqualTo("CAPTURED"); // the second call did it
    advance(QUIET); // the first call lands now; the gateway refuses it

    assertThat(sync(id).number("captured_amount")).isEqualTo(15_000);
    assertThat(atGateway(id).captured()).isEqualTo(15_000);
    assertThat(atGateway(id).charges()).isEqualTo(1);
    assertThat(entries(id)).containsExactly("CAPTURE 15000");
  }

  @Test
  void a_cancel_that_never_reached_the_gateway_is_sent_once_more() {
    String order = newOrder();
    String id = authorized(order, 15_000);
    world.simulator.plan(order, Call.VOID, Fault.TIMEOUT_BEFORE);
    assertThat(api.post("/v1/payments/" + id + "/cancel", null).status()).isEqualTo(202);

    advance(QUIET);
    Answer decided = sync(id);

    assertThat(decided.text("status")).isEqualTo("CANCELED");
    assertThat(calls(id)).containsExactly("CREATE 1", "CANCEL 2");
    assertThat(atGateway(id).status()).isEqualTo(GatewayStatus.VOIDED);
    assertThat(events(id)).containsExactly("payment.authorized", "payment.canceled");
  }

  // ---------------------------------------------------------------- refund: sent again only with a refund key

  @Test
  void a_card_refund_with_no_answer_is_never_sent_again_and_ends_without_effect() {
    String order = newOrder();
    String id = captured(order, 15_000);
    world.simulator.plan(order, Call.REFUND, Fault.TIMEOUT_BEFORE);
    assertThat(api.post("/v1/payments/" + id + "/refunds", Map.of("amount", 5_000)).status()).isEqualTo(202);

    advance(QUIET);
    Answer decided = sync(id);

    // The card gateway takes no refund key. A second call could refund twice if the first is still on its way.
    assertThat(decided.number("refunded_amount")).isZero();
    assertThat(calls(id)).containsExactly("CREATE 1", "REFUND 1");
    assertThat(results(id)).containsExactly("CREATE SUCCEEDED -", "REFUND FAILED no_effect");
    assertThat(events(id)).containsExactly("payment.captured", "refund.failed");
    assertThat(atGateway(id).refunded()).isZero();
    // The store decides: it may ask again, and the new refund is checked against fresh totals.
    assertThat(api.post("/v1/payments/" + id + "/refunds", Map.of("amount", 5_000)).text("status")).isEqualTo("SUCCEEDED");
    assertThat(atGateway(id).refunded()).isEqualTo(5_000);
  }

  @Test
  void a_card_refund_reported_as_failed_that_lands_after_all_is_found_and_counted() {
    String order = newOrder();
    String id = captured(order, 15_000);
    world.simulator.plan(order, Call.REFUND, Fault.VERY_SLOW_APPLY);
    api.post("/v1/payments/" + id + "/refunds", Map.of("amount", 5_000));
    advance(QUIET);
    sync(id);
    assertThat(results(id)).containsExactly("CREATE SUCCEEDED -", "REFUND FAILED no_effect");

    advance(QUIET); // the refund lands at the gateway now
    Answer found = sync(id);

    // The operation's status was a report. The total is the truth, and the books follow it.
    assertThat(found.number("refunded_amount")).isEqualTo(5_000);
    assertThat(entries(id)).containsExactly("CAPTURE 15000", "REFUND 5000");
    assertThat(operations(id)).containsExactly("CREATE SUCCEEDED API 15000", "REFUND FAILED API 5000", "REFUND SUCCEEDED GATEWAY 5000");
    assertThat(events(id)).containsExactly("payment.captured", "refund.failed", "refund.succeeded");
    // A new refund is checked against the fresh total: only 10,000 is left.
    assertThat(api.post("/v1/payments/" + id + "/refunds", Map.of("amount", 10_001)).code()).isEqualTo("refund_too_large");
  }

  @Test
  void a_pay_later_refund_with_no_answer_is_sent_once_more_with_the_same_key() {
    String order = newOrder();
    String id = payLaterCaptured(order);
    world.simulator.plan(order, Call.REFUND, Fault.TIMEOUT_BEFORE);
    assertThat(api.post("/v1/payments/" + id + "/refunds", Map.of("amount", 5_000)).status()).isEqualTo(202);

    advance(QUIET);
    Answer decided = sync(id);

    assertThat(decided.number("refunded_amount")).isEqualTo(5_000);
    assertThat(calls(id)).containsExactly("CREATE 1", "CAPTURE 1", "REFUND 2");
    assertThat(atGateway(gatewayId(id)).refunded()).isEqualTo(5_000);
  }

  @Test
  void a_pay_later_refund_whose_first_call_lands_late_is_done_once_thanks_to_its_key() {
    String order = newOrder();
    String id = payLaterCaptured(order);
    world.simulator.plan(order, Call.REFUND, Fault.VERY_SLOW_APPLY);
    api.post("/v1/payments/" + id + "/refunds", Map.of("amount", 5_000));

    advance(QUIET);
    sync(id); // the second call, with the same key, does the refund
    advance(QUIET); // the first call lands: the provider knows the key and does nothing
    Answer after = sync(id);

    assertThat(after.number("refunded_amount")).isEqualTo(5_000);
    assertThat(atGateway(gatewayId(id)).refunded()).isEqualTo(5_000);
    assertThat(entries(id)).containsExactly("CAPTURE 15000", "REFUND 5000");
  }

  // ---------------------------------------------------------------- nothing is decided without the gateway's word

  @Test
  void while_the_gateway_cannot_be_asked_nothing_is_decided() {
    String order = newOrder();
    String id = captured(order, 15_000);
    world.simulator.plan(order, Call.REFUND, Fault.LOST_ANSWER); // the refund was done; the answer was lost
    world.simulator.fetchDown(order, 3); // and the gateway cannot be asked: before the refund, after it, and at the next look
    assertThat(api.post("/v1/payments/" + id + "/refunds", Map.of("amount", 5_000)).status()).isEqualTo(202);

    advance(QUIET);
    sync(id); // the fetch fails: deciding "no effect" now would be wrong, the refund did happen

    assertThat(results(id)).containsExactly("CREATE SUCCEEDED -", "REFUND IN_FLIGHT -");
    assertThat(sync(id).number("refunded_amount")).isEqualTo(5_000); // the gateway is back
    assertThat(results(id)).containsExactly("CREATE SUCCEEDED -", "REFUND SUCCEEDED -");
    assertThat(atGateway(id).refunded()).isEqualTo(5_000);
  }

  @Test
  void the_worker_resolves_unknown_results_in_its_rounds() {
    String order = newOrder();
    String id = authorized(order, 15_000);
    world.simulator.plan(order, Call.CAPTURE, Fault.TIMEOUT_BEFORE);
    api.post("/v1/payments/" + id + "/capture", null);
    assertThat(time(id, "next_sync_at")).isEqualTo(now().plus(QUIET));

    advance(QUIET);
    round();

    assertThat(payment(id).text("status")).isEqualTo("CAPTURED");
  }

  /** A pay-later payment that the customer approved and Mizan captured. */
  private String payLaterCaptured(String order) {
    String id = api.post("/v1/payments", payLater(order, 15_000, "MANUAL")).text("id");
    world.simulator.customerApproves(gatewayId(id));
    sync(id);
    assertThat(api.post("/v1/payments/" + id + "/capture", null).text("status")).isEqualTo("SUCCEEDED");
    return id;
  }
}
