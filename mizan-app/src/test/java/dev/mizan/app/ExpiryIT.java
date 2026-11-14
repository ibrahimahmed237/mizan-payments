package dev.mizan.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.app.support.Api.Answer;
import dev.mizan.app.support.IntegrationTest;
import dev.mizan.core.payment.GatewayStatus;
import dev.mizan.simulator.Call;
import dev.mizan.simulator.Fault;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** A payment or a hold that waited too long ends, on fresh facts only. See docs/lld/sync.md, "Timers". */
class ExpiryIT extends IntegrationTest {

  private static final Duration CARD_WAIT = Duration.ofMinutes(30);
  private static final Duration HOLD = Duration.ofDays(7);

  // ---------------------------------------------------------------- a customer who never finishes

  @Test
  void a_payment_whose_customer_never_comes_back_expires_when_its_time_ends_not_before() {
    String id = pay(newOrder(), 15_000, "3333", "AUTO").text("id");

    advance(CARD_WAIT.minusSeconds(1));
    assertThat(sync(id).text("status")).isEqualTo("PENDING");

    advance(Duration.ofSeconds(1));
    Answer expired = sync(id);

    assertThat(expired.text("status")).isEqualTo("EXPIRED");
    assertThat(expired.json().path("next_action").isNull()).isTrue();
    assertThat(events(id)).containsExactly("payment.expired");
    assertThat(column(id, "next_sync_at")).isNull();
    assertThat(atGateway(id).status()).isEqualTo(GatewayStatus.INITIATED); // the gateway does not end it; Mizan does
  }

  @Test
  void the_worker_expires_it_in_its_rounds() {
    String id = pay(newOrder(), 15_000, "3333", "AUTO").text("id");

    advance(CARD_WAIT);
    round();

    assertThat(payment(id).text("status")).isEqualTo("EXPIRED");
  }

  @Test
  void a_customer_who_pays_in_the_last_moment_is_found_by_the_look_before_the_timer() {
    String id = pay(newOrder(), 15_000, "3333", "AUTO").text("id");
    advance(CARD_WAIT);
    world.simulator.customerApproves(id); // just before the timer runs

    Answer answer = sync(id);

    // The timer decides on fresh facts: it looked first, and the payment is paid, not expired.
    assertThat(answer.text("status")).isEqualTo("CAPTURED");
    assertThat(answer.json().path("late").asBoolean()).isFalse();
    assertThat(events(id)).containsExactly("payment.captured");
  }

  @Test
  void a_customer_who_pays_after_the_payment_expired_is_a_late_capture_and_is_returned() {
    String id = pay(newOrder(), 15_000, "3333", "AUTO").text("id");
    advance(CARD_WAIT);
    sync(id);
    assertThat(payment(id).text("status")).isEqualTo("EXPIRED"); // the store was told

    world.simulator.customerApproves(id); // the bank's page was still open in a tab
    Answer late = sync(id);

    assertThat(late.text("status")).isEqualTo("CAPTURED");
    assertThat(late.json().path("late").asBoolean()).isTrue();
    assertThat(late.number("refunded_amount")).isEqualTo(15_000);
    assertThat(events(id)).containsExactly("payment.expired", "payment.captured", "refund.succeeded");
    assertThat(atGateway(id).refunded()).isEqualTo(15_000);
  }

  @Test
  void a_payment_is_not_expired_while_the_gateway_cannot_be_asked() {
    String order = newOrder();
    String id = pay(order, 15_000, "3333", "AUTO").text("id");
    advance(CARD_WAIT);
    world.simulator.customerApproves(id);
    world.simulator.fetchDown(order, 1);

    assertThat(sync(id).text("status")).isEqualTo("PENDING"); // no fresh facts, no decision

    assertThat(sync(id).text("status")).isEqualTo("CAPTURED"); // and it was paid after all
  }

  @Test
  void a_pay_later_session_that_the_customer_leaves_expires_as_the_provider_says() {
    String id = api.post("/v1/payments", payLater(newOrder(), 15_000, "MANUAL")).text("id");
    world.simulator.customerLeaves(gatewayId(id));

    Answer expired = sync(id);

    assertThat(expired.text("status")).isEqualTo("EXPIRED");
    assertThat(events(id)).containsExactly("payment.expired");
  }

  @Test
  void a_pay_later_payment_waits_two_hours() {
    String id = api.post("/v1/payments", payLater(newOrder(), 15_000, "MANUAL")).text("id");

    advance(Duration.ofHours(2).minusSeconds(1));
    assertThat(sync(id).text("status")).isEqualTo("PENDING");
    advance(Duration.ofSeconds(1));

    assertThat(sync(id).text("status")).isEqualTo("EXPIRED");
  }

  // ---------------------------------------------------------------- a hold that is never captured

  @Test
  void a_hold_that_is_not_captured_in_time_ends_and_is_released_at_the_gateway() {
    String id = authorized(newOrder(), 15_000);

    advance(HOLD.minusSeconds(1));
    assertThat(sync(id).text("status")).isEqualTo("AUTHORIZED");

    advance(Duration.ofSeconds(1));
    Answer expired = sync(id);

    // The gateway may show "authorized" for ever after the bank let the money go (assumption A4). Mizan ends it.
    assertThat(expired.text("status")).isEqualTo("EXPIRED");
    assertThat(atGateway(id).status()).isEqualTo(GatewayStatus.VOIDED);
    assertThat(operations(id)).containsExactly("CREATE SUCCEEDED API 15000", "CANCEL SUCCEEDED SYSTEM 0");
    assertThat(events(id)).containsExactly("payment.authorized", "payment.expired");
    assertThat(entries(id)).isEmpty();
    assertThat(column(id, "next_sync_at")).isNull();
  }

  @Test
  void the_worker_ends_a_hold_in_its_rounds() {
    String id = authorized(newOrder(), 15_000);
    assertThat(time(id, "next_sync_at")).isEqualTo(now().plus(HOLD));

    advance(HOLD);
    round();

    assertThat(payment(id).text("status")).isEqualTo("EXPIRED");
    assertThat(atGateway(id).status()).isEqualTo(GatewayStatus.VOIDED);
  }

  @Test
  void a_hold_is_not_ended_while_a_capture_is_in_flight() {
    String order = newOrder();
    String id = authorized(order, 15_000);
    advance(HOLD.minusSeconds(30));
    world.simulator.plan(order, Call.CAPTURE, Fault.TIMEOUT_BEFORE);
    assertThat(api.post("/v1/payments/" + id + "/capture", null).status()).isEqualTo(202); // sent in time; no answer

    advance(Duration.ofSeconds(30)); // the hold's time is over, and the capture's result is not known
    assertThat(sync(id).text("status")).isEqualTo("AUTHORIZED"); // the operation is settled first (rule R4)

    advance(Duration.ofSeconds(30)); // one quiet minute after the capture's call
    assertThat(sync(id).text("status")).isEqualTo("CAPTURED"); // sent once more, and it was captured
    assertThat(events(id)).containsExactly("payment.authorized", "payment.captured");
  }

  @Test
  void a_hold_whose_release_gets_no_answer_is_tried_once_more_and_then_left() {
    String order = newOrder();
    String id = authorized(order, 15_000);
    advance(HOLD);
    world.simulator.plan(order, Call.VOID, Fault.TIMEOUT_BEFORE);
    world.simulator.plan(order, Call.VOID, Fault.TIMEOUT_BEFORE);

    sync(id); // expired; the release is sent; no answer
    assertThat(payment(id).text("status")).isEqualTo("EXPIRED");
    advance(Duration.ofMinutes(1));
    sync(id); // sent once more; no answer
    advance(Duration.ofMinutes(1));
    sync(id); // ends without effect
    sync(id);
    sync(id);

    // The payment is ended for the store either way. The follow-up is not started a second time.
    assertThat(operations(id)).containsExactly("CREATE SUCCEEDED API 15000", "CANCEL FAILED SYSTEM 0");
    assertThat(events(id)).containsExactly("payment.authorized", "payment.expired", "cancel.failed");
    assertThat(column(id, "follow_ups_started")).isEqualTo("AUTO_VOID");
    assertThat(column(id, "next_sync_at")).isNull();
  }
}
