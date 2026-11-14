package dev.mizan.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.app.support.Api;
import dev.mizan.app.support.Api.Answer;
import dev.mizan.app.support.IntegrationTest;
import dev.mizan.core.payment.GatewayStatus;
import dev.mizan.simulator.Call;
import dev.mizan.simulator.Fault;
import dev.mizan.simulator.Simulator;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A webhook from a gateway comes in: each row of the case table. See docs/lld/workers.md. */
class WebhookIT extends IntegrationTest {

  /** Webhooks need no API key. */
  private final Api gateway = api.withKey(null);

  private String cardSecret() {
    return world.simulator.config().cardWebhookSecret();
  }

  /** A card webhook as the gateway writes it. */
  private static String cardBody(String eventId, String type, String paymentId, String secret, String status) {
    return "{\"id\":\"" + eventId + "\",\"type\":\"" + type + "\",\"created_at\":\"2026-11-14T09:00:00Z\",\"secret_token\":\"" + secret
        + "\",\"live\":false,\"data\":{\"id\":\"" + paymentId + "\",\"status\":\"" + status + "\",\"amount\":15000,\"currency\":\"SAR\"}}";
  }

  private Answer card(String body) {
    return gateway.send("POST", "/v1/webhooks/card", body, null);
  }

  /** A pay-later webhook: the payment object, with the event as its status. */
  private static String payLaterBody(String providerId, String status, String mizanId) {
    return "{\"id\":\"" + providerId + "\",\"status\":\"" + status + "\",\"amount\":\"150.00\",\"currency\":\"SAR\","
        + "\"order\":{\"reference_id\":\"" + mizanId + "\"}}";
  }

  private Answer payLater(String body, String signature) {
    var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(world.baseUrl + "/v1/webhooks/pay-later"))
        .header("Content-Type", "application/json")
        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
    if (signature != null) {
      request.header("X-Signature", signature);
    }
    try {
      var response = java.net.http.HttpClient.newHttpClient().send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
      return new Answer(response.statusCode(), response);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private String sign(String body) {
    return Simulator.sign(world.simulator.config().payLaterWebhookSecret(), body);
  }

  private static String newEvent() {
    return "evt_" + UUID.randomUUID();
  }

  private long eventRows(String eventId) {
    return jdbc.sql("SELECT count(*) FROM gateway_events WHERE event_id = ?").param(eventId).query(Long.class).single();
  }

  private List<String> unknownPaymentDiscrepancies(String gatewayPaymentId) {
    return jdbc.sql("SELECT type || ' ' || status || ' ' || gateway FROM discrepancies WHERE gateway_payment_id = ?")
        .param(gatewayPaymentId).query(String.class).list();
  }

  // ---------------------------------------------------------------- a webhook is a hint

  @Test
  void a_webhook_only_asks_for_a_look_and_changes_nothing_by_itself() {
    String id = pay(newOrder(), 15_000, "3333", "AUTO").text("id");
    String event = newEvent();

    Answer answer = card(cardBody(event, "payment_paid", id, cardSecret(), "paid")); // it says "paid". It is not.

    assertThat(answer.status()).isEqualTo(200);
    assertThat(payment(id).text("status")).isEqualTo("PENDING"); // the status in a webhook is never used (rule R2)
    assertThat(entries(id)).isEmpty();
    assertThat(webhooks(id)).containsExactly("payment_paid");
    assertThat(time(id, "next_sync_at")).isEqualTo(now()); // the worker is asked to look now
    // The look asks the gateway, which knows the truth: the customer has not paid.
    assertThat(sync(id).text("status")).isEqualTo("PENDING");
    assertThat(atGateway(id).charges()).isZero();
  }

  @Test
  void after_a_true_webhook_the_look_finds_the_money() {
    String id = pay(newOrder(), 15_000, "3333", "AUTO").text("id");
    world.simulator.customerApproves(id);

    card(cardBody(newEvent(), "payment_paid", id, cardSecret(), "paid"));
    int taken = round();

    assertThat(taken).isGreaterThanOrEqualTo(1);
    assertThat(payment(id).text("status")).isEqualTo("CAPTURED");
    assertThat(column(id, "next_sync_at")).isNull();
  }

  @Test
  void the_same_event_twice_is_written_down_once_and_asks_for_one_look() {
    String id = pay(newOrder(), 15_000, "3333", "AUTO").text("id");
    String event = newEvent();
    String body = cardBody(event, "payment_paid", id, cardSecret(), "paid");

    assertThat(card(body).status()).isEqualTo(200);
    sync(id); // the look that the first one asked for
    Answer repeat = card(body);

    assertThat(repeat.status()).isEqualTo(200); // so the gateway stops sending it
    assertThat(eventRows(event)).isEqualTo(1);
    assertThat(time(id, "next_sync_at")).isNotEqualTo(now()); // the repeat did not ask for another look
  }

  @Test
  void events_in_any_order_give_the_same_payment() {
    String id = pay(newOrder(), 15_000, "3333", "AUTO").text("id");
    world.simulator.customerApproves(id);
    world.simulator.cardRefund(id, 5_000L);

    // "refunded" arrives before "paid": the order of events means nothing, because their statuses are not used.
    card(cardBody(newEvent(), "payment_refunded", id, cardSecret(), "refunded"));
    sync(id);
    card(cardBody(newEvent(), "payment_paid", id, cardSecret(), "paid"));
    sync(id);

    assertThat(payment(id).number("captured_amount")).isEqualTo(15_000);
    assertThat(payment(id).number("refunded_amount")).isEqualTo(5_000);
    assertThat(entries(id)).containsExactly("CAPTURE 15000", "REFUND 5000");
  }

  // ---------------------------------------------------------------- the check

  @Test
  void a_wrong_secret_a_missing_secret_and_a_body_that_is_not_json_are_refused_and_nothing_is_saved() {
    String id = pay(newOrder(), 15_000, "3333", "AUTO").text("id");
    String event = newEvent();

    Answer wrong = card(cardBody(event, "payment_paid", id, "not-the-secret", "paid"));
    Answer almost = card(cardBody(event, "payment_paid", id, cardSecret() + "x", "paid"));
    Answer missing = card("{\"id\":\"" + event + "\",\"type\":\"payment_paid\",\"data\":{\"id\":\"" + id + "\"}}");
    Answer notJson = card("secret_token=" + cardSecret());
    Answer array = card("[\"" + cardSecret() + "\"]");

    assertThat(List.of(wrong.status(), almost.status(), missing.status(), notJson.status(), array.status())).containsOnly(401);
    assertThat(wrong.code()).isEqualTo("unauthorized");
    assertThat(wrong.raw().body()).doesNotContain(cardSecret());
    assertThat(eventRows(event)).isZero();
    assertThat(webhooks(id)).isEmpty();
    assertThat(time(id, "next_sync_at")).isEqualTo(now().plusSeconds(15)); // untouched: still the worker's own first look
  }

  @Test
  void a_webhook_that_does_not_say_which_event_or_which_payment_is_refused() {
    String secret = cardSecret();

    Answer noEvent = card("{\"type\":\"payment_paid\",\"secret_token\":\"" + secret + "\",\"data\":{\"id\":\"p1\"}}");
    Answer noPayment = card("{\"id\":\"" + newEvent() + "\",\"type\":\"payment_paid\",\"secret_token\":\"" + secret + "\",\"data\":{}}");
    Answer noType = card("{\"id\":\"" + newEvent() + "\",\"secret_token\":\"" + secret + "\",\"data\":{\"id\":\"p1\"}}");
    Answer numberId = card("{\"id\":7,\"type\":\"payment_paid\",\"secret_token\":\"" + secret + "\",\"data\":{\"id\":\"p1\"}}");
    Answer longId = card(cardBody("e".repeat(256), "payment_paid", "p1", secret, "paid"));

    assertThat(List.of(noEvent.status(), noPayment.status(), noType.status(), numberId.status(), longId.status())).containsOnly(400);
    assertThat(noEvent.code()).isEqualTo("malformed_body");
  }

  @Test
  void a_body_over_64_kb_is_not_read() {
    String huge = cardBody(newEvent(), "payment_paid", "p1", cardSecret(), "x".repeat(70_000));
    String large = cardBody(newEvent(), "payment_paid", "p-" + UUID.randomUUID(), cardSecret(), "x".repeat(60_000));

    Answer refused = card(huge);

    assertThat(refused.status()).isEqualTo(413);
    assertThat(refused.code()).isEqualTo("body_too_large");
    assertThat(card(large).status()).isEqualTo(200); // under the limit
  }

  @Test
  void a_gateway_that_does_not_exist_is_not_found_and_webhooks_need_no_api_key() {
    assertThat(gateway.send("POST", "/v1/webhooks/other", cardBody(newEvent(), "payment_paid", "p1", cardSecret(), "paid"), null).status()).isEqualTo(404);
    // A made-up name leaves no metric behind: anyone can send these, and each name would stay in memory for good.
    assertThat(world.app.getBean(MeterRegistry.class).find("mizan.webhooks").tag("gateway", "other").counters()).isEmpty();
    assertThat(gateway.get("/v1/webhooks/card").status()).isEqualTo(405);
    assertThat(gateway.get("/v1/payments").status()).isEqualTo(401); // the API itself still needs a key
  }

  // ---------------------------------------------------------------- a payment Mizan does not have

  @Test
  void an_event_about_a_payment_mizan_does_not_have_opens_one_discrepancy() {
    String unknown = "pay_" + UUID.randomUUID();
    long eventsBefore = jdbc.sql("SELECT count(*) FROM outbox_events WHERE payment_id IS NULL").query(Long.class).single();

    Answer first = card(cardBody(newEvent(), "payment_paid", unknown, cardSecret(), "paid"));
    Answer second = card(cardBody(newEvent(), "payment_refunded", unknown, cardSecret(), "refunded"));

    // Someone took a payment at the gateway outside Mizan. The gateway gets 200; a person gets a discrepancy.
    assertThat(first.status()).isEqualTo(200);
    assertThat(second.status()).isEqualTo(200);
    assertThat(webhooks(unknown)).containsExactly("payment_paid", "payment_refunded");
    assertThat(unknownPaymentDiscrepancies(unknown)).containsExactly("UNKNOWN_PAYMENT_EVENT OPEN CARD_GATEWAY"); // one, not two
    String event = jdbc.sql("SELECT payload FROM outbox_events WHERE payment_id IS NULL ORDER BY seq DESC LIMIT 1").query(String.class).single();
    assertThat(event).contains("\"type\":\"discrepancy.opened\"").contains("UNKNOWN_PAYMENT_EVENT").contains("\"payment\":null");
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_events WHERE payment_id IS NULL").query(Long.class).single()).isEqualTo(eventsBefore + 1);
  }

  @Test
  void an_event_cannot_take_over_a_payment_that_has_its_own_id_at_the_gateway() {
    String card = pay(newOrder(), 15_000, "3333", "AUTO").text("id");
    String order = newOrder();
    world.simulator.plan(order, Call.CREATE, Fault.LOST_ANSWER);
    String payLaterWithoutId = api.post("/v1/payments", payLater(order, 15_000, "MANUAL")).text("id");
    String foreign = "pay_" + UUID.randomUUID();

    // An event about an unknown gateway payment that names one of Mizan's payments as its own.
    String body = cardBody(newEvent(), "payment_paid", foreign, cardSecret(), "paid")
        .replace("\"status\":\"paid\"", "\"status\":\"paid\",\"metadata\":{\"mizan_payment_id\":\"" + card + "\"}");
    String other = cardBody(newEvent(), "payment_paid", foreign + "-2", cardSecret(), "paid")
        .replace("\"status\":\"paid\"", "\"status\":\"paid\",\"metadata\":{\"mizan_payment_id\":\"" + payLaterWithoutId + "\"}");
    card(body);
    card(other);

    // The card payment has its own id at the gateway. The pay-later payment has none, but it is not the card gateway's.
    assertThat(gatewayId(card)).isEqualTo(card);
    assertThat(gatewayId(payLaterWithoutId)).isNull();
    assertThat(unknownPaymentDiscrepancies(foreign)).hasSize(1);
    assertThat(unknownPaymentDiscrepancies(foreign + "-2")).hasSize(1);
  }

  // ---------------------------------------------------------------- pay later

  @Test
  void a_pay_later_webhook_is_checked_by_its_signature() {
    String id = api.post("/v1/payments", payLater(newOrder(), 15_000, "MANUAL")).text("id");
    String providerId = gatewayId(id);
    String body = payLaterBody(providerId, "authorized", id);

    Answer good = payLater(body, sign(body));

    assertThat(good.status()).isEqualTo(200);
    assertThat(webhooks(providerId)).containsExactly("authorized");
    assertThat(time(id, "next_sync_at")).isEqualTo(now());
    assertThat(payment(id).text("status")).isEqualTo("PENDING"); // it said "authorized". The customer did nothing.
  }

  @Test
  void a_pay_later_webhook_with_a_wrong_or_missing_signature_or_a_changed_body_is_refused() {
    String id = api.post("/v1/payments", payLater(newOrder(), 15_000, "MANUAL")).text("id");
    String providerId = gatewayId(id);
    String body = payLaterBody(providerId, "authorized", id);
    String changed = body.replace("150.00", "1.00");

    Answer wrong = payLater(body, "0".repeat(64));
    Answer missing = payLater(body, null);
    Answer otherBody = payLater(changed, sign(body));
    Answer otherSecret = payLater(body, Simulator.sign("another-secret", body));
    Answer signedButNotJson = payLater("not json", sign("not json"));

    assertThat(List.of(wrong.status(), missing.status(), otherBody.status(), otherSecret.status())).containsOnly(401);
    assertThat(signedButNotJson.status()).isEqualTo(400);
    assertThat(webhooks(providerId)).isEmpty();
  }

  @Test
  void the_same_pay_later_webhook_twice_is_one_event_though_it_has_no_event_id() {
    String id = api.post("/v1/payments", payLater(newOrder(), 15_000, "MANUAL")).text("id");
    String providerId = gatewayId(id);
    String body = payLaterBody(providerId, "authorized", id);

    payLater(body, sign(body));
    payLater(body, sign(body));
    String other = payLaterBody(providerId, "closed", id);
    payLater(other, sign(other));

    assertThat(webhooks(providerId)).containsExactly("authorized", "closed");
  }

  @Test
  void a_webhook_ties_a_pay_later_session_to_a_payment_whose_create_answer_was_lost() {
    String order = newOrder();
    world.webhooksFor(order);
    world.simulator.plan(order, Call.CREATE, Fault.LOST_ANSWER);
    String id = api.post("/v1/payments", payLater(order, 15_000, "MANUAL")).text("id");
    assertThat(payment(id).json().path("failure").path("code").asString()).isEqualTo("gateway_unreachable");
    assertThat(gatewayId(id)).isNull(); // Mizan never learned the provider's id
    String providerId = world.simulator.paymentsOf(order).get(0).id;

    // Against assumption A6, the customer does reach the provider's page and approves.
    world.simulator.customerApproves(providerId);
    await(() -> gatewayId(id) != null);
    round();

    // The webhook carries Mizan's payment id. Now Mizan can ask, sees a hold on a payment it has failed, and releases it.
    assertThat(gatewayId(id)).isEqualTo(providerId);
    assertThat(payment(id).text("status")).isEqualTo("FAILED");
    assertThat(atGateway(providerId).status()).isEqualTo(GatewayStatus.VOIDED);
    assertThat(operations(id)).containsExactly("CREATE FAILED API 15000", "CANCEL SUCCEEDED SYSTEM 0");
  }

  // ---------------------------------------------------------------- the simulator's webhook faults, end to end

  @Test
  void webhooks_sent_three_times_are_one_event_each() {
    String order = newOrder();
    world.webhooksFor(order);
    world.simulator.stick(order, Fault.WEBHOOK_DUP);

    String id = pay(order, 15_000, "1111", "AUTO").text("id");
    await(() -> world.webhooksSent.stream().filter(sent -> sent.startsWith(order + ":")).count() == 3);
    await(() -> !webhooks(id).isEmpty());

    assertThat(webhooks(id)).containsExactly("payment_paid");
  }

  @Test
  void a_webhook_that_arrives_before_the_answer_to_the_create_finds_the_payment() {
    String order = newOrder();
    world.webhooksFor(order);
    world.simulator.stick(order, Fault.WEBHOOK_EARLY);

    String id = pay(order, 15_000, "1111", "AUTO").text("id");

    // The payment's row was committed before the call (transaction 1), so the early webhook is about a known payment.
    assertThat(webhooks(id)).containsExactly("payment_paid");
    assertThat(jdbc.sql("SELECT payment_id::text FROM gateway_events WHERE gateway_payment_id = ?").param(id).query(String.class).single()).isEqualTo(id);
    assertThat(unknownPaymentDiscrepancies(id)).isEmpty();
    assertThat(payment(id).text("status")).isEqualTo("CAPTURED");
  }

  @Test
  void a_forged_webhook_with_the_right_secret_cannot_move_money() {
    String order = newOrder();
    world.webhooksFor(order);
    world.simulator.stick(order, Fault.WEBHOOK_FORGED);

    String id = pay(order, 15_000, "3333", "AUTO").text("id"); // the customer has not paid
    await(() -> world.webhooksSent.stream().filter(sent -> sent.startsWith(order + ":")).count() == 2);
    await(() -> !webhooks(id).isEmpty());
    round();

    // One forged webhook had a wrong secret and was refused. The other had the right secret and said "paid".
    assertThat(webhooks(id)).containsExactly("payment_paid");
    assertThat(payment(id).text("status")).isEqualTo("PENDING");
    assertThat(entries(id)).isEmpty();
  }

  @Test
  void a_late_webhook_is_harmless() {
    String order = newOrder();
    world.webhooksFor(order);
    world.simulator.stick(order, Fault.WEBHOOK_LATE);
    String id = pay(order, 15_000, "1111", "AUTO").text("id");
    assertThat(webhooks(id)).isEmpty();

    advance(Duration.ofMinutes(5)); // the simulator sends it now
    await(() -> !webhooks(id).isEmpty());
    sync(id);

    assertThat(payment(id).text("status")).isEqualTo("CAPTURED");
    assertThat(entries(id)).containsExactly("CAPTURE 15000"); // once
    assertThat(events(id)).containsExactly("payment.captured");
  }

  // ---------------------------------------------------------------- a hint is not lost

  @Test
  void a_hint_that_comes_while_a_look_is_on_its_way_to_the_gateway_is_kept() throws Exception {
    String order = newOrder();
    String id = pay(order, 15_000, "3333", "AUTO").text("id");
    world.simulator.plan(order, Call.FETCH, Fault.TIMEOUT_BEFORE); // the next fetch hangs until Mizan gives up

    Thread look = new Thread(() -> sync(id));
    look.start();
    Thread.sleep(150); // the look is waiting for the gateway now
    card(cardBody(newEvent(), "payment_paid", id, cardSecret(), "paid"));
    look.join();

    // The look failed. Without the hint, the next look would be 30 seconds away. With it, the payment stays due.
    assertThat(column(id, "sync_failures")).isEqualTo("1");
    assertThat(time(id, "next_sync_at")).isEqualTo(now());
  }

  @Test
  void a_look_that_is_not_the_workers_leaves_the_request_standing() {
    String order = newOrder();
    String id = pay(order, 15_000, "3333", "AUTO").text("id");
    advance(Duration.ofSeconds(5));
    world.simulator.customerApproves(id);
    card(cardBody(newEvent(), "payment_paid", id, cardSecret(), "paid"));

    api.withKey(null).get("/v1/return/" + id); // the customer comes back: a look, but not the worker's

    // The look found the money. The request stays up all the same: only a worker's claim, taken before its fetch,
    // proves that a look started after the request. One look too many is safe; one too few is not.
    assertThat(payment(id).text("status")).isEqualTo("CAPTURED");
    assertThat(column(id, "sync_requested")).isEqualTo("true");
    assertThat(time(id, "next_sync_at")).isEqualTo(now());
    round();
    assertThat(column(id, "sync_requested")).isEqualTo("false");
    assertThat(column(id, "next_sync_at")).isNull();
  }

  @Test
  void a_hint_that_came_before_the_look_started_is_answered_by_that_look() {
    String id = pay(newOrder(), 15_000, "3333", "AUTO").text("id");
    world.simulator.customerApproves(id);
    card(cardBody(newEvent(), "payment_paid", id, cardSecret(), "paid"));
    assertThat(time(id, "next_sync_at")).isEqualTo(now());

    sync(id);

    assertThat(payment(id).text("status")).isEqualTo("CAPTURED");
    assertThat(column(id, "next_sync_at")).isNull(); // nothing is waiting any more
  }
}
