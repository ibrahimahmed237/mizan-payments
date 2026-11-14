package dev.mizan.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.app.support.IntegrationTest;
import dev.mizan.simulator.Call;
import dev.mizan.simulator.Fault;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Operations Mizan starts by itself, and that each is tried once. See docs/lld/payments.md, "Follow-ups". */
class FollowUpIT extends IntegrationTest {

  /** A payment that the store canceled, and that the customer paid afterwards. */
  private String paidAfterCancel(String order) {
    String id = pay(order, 15_000, "3333", "AUTO").text("id");
    api.post("/v1/payments/" + id + "/cancel", null);
    world.simulator.customerApproves(id);
    return id;
  }

  @Test
  void the_return_of_a_late_capture_is_an_operation_of_mizans_own() {
    String id = paidAfterCancel(newOrder());

    sync(id);

    assertThat(operations(id)).containsExactly(
        "CREATE SUCCEEDED API 15000", "CANCEL SUCCEEDED API 0", "REFUND SUCCEEDED SYSTEM 15000");
    assertThat(column(id, "follow_up")).isNull();
    assertThat(column(id, "follow_ups_started")).isEqualTo("AUTO_REFUND");
    assertThat(column(id, "next_sync_at")).isNull();
  }

  @Test
  void a_return_that_fails_is_told_to_the_store_and_is_not_started_again() {
    String order = newOrder();
    String id = paidAfterCancel(order);
    world.simulator.plan(order, Call.REFUND, Fault.TIMEOUT_BEFORE);

    sync(id); // the late capture is recorded; the refund is sent; no answer
    advance(Duration.ofMinutes(1));
    sync(id); // a card refund is never sent again: it ends without effect
    sync(id);
    advance(Duration.ofHours(1));
    sync(id);

    assertThat(operations(id)).containsExactly(
        "CREATE SUCCEEDED API 15000", "CANCEL SUCCEEDED API 0", "REFUND FAILED SYSTEM 15000"); // one refund, not three
    assertThat(events(id)).containsExactly("payment.canceled", "payment.captured", "refund.failed");
    assertThat(payment(id).number("refunded_amount")).isZero(); // the payment shows what is left
    assertThat(atGateway(id).refunded()).isZero();
    // The store, told of the failure, can return the money itself.
    assertThat(api.post("/v1/payments/" + id + "/refunds", null).text("status")).isEqualTo("SUCCEEDED");
    assertThat(atGateway(id).refunded()).isEqualTo(15_000);
  }

  @Test
  void a_return_is_dropped_when_the_money_went_back_in_another_way() {
    String id = paidAfterCancel(newOrder());
    world.simulator.externalRefund(id, 15_000); // someone refunded it in the gateway's dashboard first

    sync(id);

    // The look saw the capture and the refund together. Nothing is left to return, so no refund is sent.
    assertThat(payment(id).number("refunded_amount")).isEqualTo(15_000);
    assertThat(operations(id)).containsExactly(
        "CREATE SUCCEEDED API 15000", "CANCEL SUCCEEDED API 0", "REFUND SUCCEEDED GATEWAY 15000");
    assertThat(column(id, "follow_up")).isNull();
    assertThat(column(id, "follow_ups_started")).isEmpty(); // dropped is not started
    assertThat(atGateway(id).refunded()).isEqualTo(15_000);
  }

  @Test
  void an_automatic_capture_that_gets_no_answer_twice_fails_and_the_hold_stays_for_the_store() {
    String order = newOrder();
    String id = api.post("/v1/payments", payLater(order, 15_000, "AUTO")).text("id");
    world.simulator.customerApproves(gatewayId(id));
    world.simulator.plan(order, Call.CAPTURE, Fault.TIMEOUT_BEFORE);
    world.simulator.plan(order, Call.CAPTURE, Fault.TIMEOUT_BEFORE);

    sync(id);
    advance(Duration.ofMinutes(1));
    sync(id);
    advance(Duration.ofMinutes(1));
    sync(id);
    sync(id);

    assertThat(payment(id).text("status")).isEqualTo("AUTHORIZED");
    assertThat(operations(id)).containsExactly("CREATE SUCCEEDED API 15000", "CAPTURE FAILED SYSTEM 15000");
    assertThat(events(id)).containsExactly("payment.authorized", "capture.failed");
    assertThat(api.post("/v1/payments/" + id + "/capture", null).text("status")).isEqualTo("SUCCEEDED"); // the store captures itself
  }

  @Test
  void while_mizans_own_operation_is_in_flight_the_store_must_wait_like_for_any_other() {
    String order = newOrder();
    String id = paidAfterCancel(order);
    world.simulator.plan(order, Call.REFUND, Fault.TIMEOUT_BEFORE);
    sync(id); // Mizan's own refund is in flight

    var answer = api.post("/v1/payments/" + id + "/refunds", null);

    assertThat(answer.status()).isEqualTo(409);
    assertThat(answer.code()).isEqualTo("operation_in_progress"); // rule R4 holds for Mizan's own operations too
  }
}
