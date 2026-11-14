package dev.mizan.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.simulator.Kit.Received;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WebhookTest {

  private final Kit kit = new Kit();

  @AfterEach
  void stop() {
    kit.close();
  }

  private List<String> types() {
    return kit.webhooks.stream().map(w -> w.json().path("type").asString(w.json().path("status").asString())).toList();
  }

  @Test
  void the_card_gateway_sends_the_event_with_its_shared_secret_in_the_body() throws IOException {
    String id = kit.cardPayment("1111", 15_000, false, "order-1");
    Kit.await(() -> kit.webhooks.size() == 1);

    Received webhook = kit.webhooks.get(0);

    assertThat(webhook.json().path("type").asString()).isEqualTo("payment_paid");
    assertThat(webhook.json().path("id").asString()).startsWith("evt_");
    assertThat(webhook.json().path("secret_token").asString()).isEqualTo(kit.simulator.config().cardWebhookSecret());
    assertThat(webhook.json().path("data").path("id").asString()).isEqualTo(id);
    assertThat(webhook.json().path("data").path("status").asString()).isEqualTo("paid");
    assertThat(webhook.signature()).isNull();
  }

  @Test
  void each_change_of_a_card_payment_has_its_event() throws IOException {
    String id = kit.cardPayment("1111", 15_000, true, "order-1");
    kit.card("POST", "/payments/" + id + "/capture", "{}");
    kit.card("POST", "/payments/" + id + "/refund", "{\"amount\":100}");
    kit.cardPayment("0002", 15_000, false, "order-2");
    String voided = kit.cardPayment("1111", 15_000, true, "order-3");
    kit.card("POST", "/payments/" + voided + "/void", "{}");
    Kit.await(() -> kit.webhooks.size() == 6);

    assertThat(types()).containsExactlyInAnyOrder(
        "payment_authorized", "payment_captured", "payment_refunded", "payment_failed", "payment_authorized", "payment_voided");
  }

  @Test
  void a_payment_that_waits_for_the_customer_has_no_event_yet() throws IOException {
    kit.cardPayment("3333", 15_000, false, "order-1");
    Kit.settle();

    assertThat(kit.webhooks).isEmpty();
  }

  @Test
  void the_pay_later_provider_signs_the_body_and_writes_the_status_in_lower_case() throws IOException {
    String id = kit.payLaterPayment("150.00", "order-1");
    kit.simulator.customerApproves(id);
    Kit.await(() -> kit.webhooks.size() == 1);

    Received webhook = kit.webhooks.get(0);

    assertThat(webhook.json().path("status").asString()).isEqualTo("authorized");
    assertThat(webhook.json().path("id").asString()).isEqualTo(id);
    assertThat(webhook.json().path("order").path("reference_id").asString()).isEqualTo(kit.simulator.payment(id).mizanPaymentId);
    assertThat(webhook.signature()).isEqualTo(Simulator.sign(kit.simulator.config().payLaterWebhookSecret(), webhook.body()));
    assertThat(webhook.signature()).isNotEqualTo(Simulator.sign("another secret", webhook.body()));
  }

  @Test
  void a_failed_delivery_is_tried_again_after_a_minute() throws IOException {
    kit.failNextWebhooks.set(1);
    kit.cardPayment("1111", 15_000, false, "order-1");
    Kit.await(() -> kit.webhooks.size() == 1);

    kit.advance(Duration.ofSeconds(59));
    Kit.settle();
    int beforeTheMinute = kit.webhooks.size();
    kit.advance(Duration.ofSeconds(1));
    Kit.await(() -> kit.webhooks.size() == 2);

    assertThat(beforeTheMinute).isEqualTo(1);
    assertThat(kit.webhooks.get(1).body()).isEqualTo(kit.webhooks.get(0).body());
  }

  @Test
  void a_webhook_is_dropped_after_six_tries() throws IOException {
    kit.failNextWebhooks.set(100);
    kit.cardPayment("1111", 15_000, false, "order-1");
    Kit.await(() -> kit.webhooks.size() == 1);

    for (Duration wait : List.of(Duration.ofMinutes(1), Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofHours(2))) {
      int before = kit.webhooks.size();
      kit.advance(wait);
      Kit.await(() -> kit.webhooks.size() == before + 1);
    }
    kit.advance(Duration.ofDays(2));
    Kit.settle();

    assertThat(kit.webhooks).hasSize(6);
  }

  @Test
  void webhook_drop_sends_nothing() throws IOException {
    kit.simulator.stick("order-1", Fault.WEBHOOK_DROP);
    kit.cardPayment("1111", 15_000, false, "order-1");
    kit.advance(Duration.ofHours(5));
    Kit.settle();

    assertThat(kit.webhooks).isEmpty();
  }

  @Test
  void webhook_dup_sends_each_event_three_times() throws IOException {
    kit.simulator.stick("order-1", Fault.WEBHOOK_DUP);
    kit.cardPayment("1111", 15_000, false, "order-1");
    Kit.await(() -> kit.webhooks.size() == 3);
    Kit.settle();

    assertThat(kit.webhooks).hasSize(3);
    assertThat(kit.webhooks.stream().map(Received::body).distinct()).hasSize(1);
  }

  @Test
  void webhook_late_sends_the_event_five_minutes_later() throws IOException {
    kit.simulator.stick("order-1", Fault.WEBHOOK_LATE);
    kit.cardPayment("1111", 15_000, false, "order-1");
    kit.advance(Duration.ofSeconds(299));
    Kit.settle();
    int early = kit.webhooks.size();

    kit.advance(Duration.ofSeconds(1));
    Kit.await(() -> kit.webhooks.size() == 1);

    assertThat(early).isZero();
  }

  @Test
  void webhook_reorder_delivers_a_later_event_before_an_earlier_one() throws IOException {
    kit.simulator.stick("order-1", Fault.WEBHOOK_REORDER);
    String id = kit.cardPayment("1111", 15_000, true, "order-1");
    kit.card("POST", "/payments/" + id + "/capture", "{}");
    kit.card("POST", "/payments/" + id + "/refund", "{\"amount\":100}");

    kit.advance(Duration.ofSeconds(10));
    Kit.await(() -> kit.webhooks.size() == 1);
    kit.advance(Duration.ofSeconds(10));
    Kit.await(() -> kit.webhooks.size() == 2);
    kit.advance(Duration.ofSeconds(10));
    Kit.await(() -> kit.webhooks.size() == 3);

    assertThat(types()).containsExactly("payment_refunded", "payment_captured", "payment_authorized");
  }

  @Test
  void webhook_forged_sends_a_wrong_secret_and_a_false_claim_with_the_right_secret() throws IOException {
    kit.simulator.stick("order-1", Fault.WEBHOOK_FORGED);
    String id = kit.cardPayment("3333", 15_000, false, "order-1");
    Kit.await(() -> kit.webhooks.size() == 2);

    List<String> secrets = kit.webhooks.stream().map(w -> w.json().path("secret_token").asString()).toList();
    Received falseClaim = kit.webhooks.stream()
        .filter(w -> w.json().path("secret_token").asString().equals(kit.simulator.config().cardWebhookSecret())).findFirst().orElseThrow();

    assertThat(secrets).containsExactlyInAnyOrder("not-the-secret", kit.simulator.config().cardWebhookSecret());
    assertThat(falseClaim.json().path("type").asString()).isEqualTo("payment_paid");
    assertThat(falseClaim.json().path("data").path("status").asString()).isEqualTo("paid");
    assertThat(kit.simulator.payment(id).charges()).isZero();
  }

  @Test
  void webhook_early_arrives_before_the_call_is_answered() throws IOException {
    kit.simulator.stick("order-1", Fault.WEBHOOK_EARLY);

    String token = kit.token("1111");
    int answered = kit.answers.get();

    kit.card("POST", "/payments", Kit.createBody(Kit.newId(), 15_000, token, false, "order-1"));

    assertThat(kit.webhooks).hasSize(1); // no waiting: it was delivered before the create call returned
    assertThat(kit.webhooks.get(0).answersBefore()).isEqualTo(answered); // the create call had no answer yet
  }

  @Test
  void a_refund_made_outside_the_api_has_an_event_at_the_card_gateway_only() throws IOException {
    String card = kit.cardPayment("1111", 15_000, false, "order-1");
    String payLater = kit.payLaterPayment("150.00", "order-2");
    kit.simulator.customerApproves(payLater);
    kit.payLater("POST", "/payments/" + payLater + "/captures", "{\"amount\":\"150.00\"}");
    Kit.await(() -> kit.webhooks.size() == 3);

    kit.simulator.externalRefund(card, 500);
    kit.simulator.externalRefund(payLater, 500);
    Kit.await(() -> kit.webhooks.size() == 4);
    Kit.settle();

    assertThat(types()).containsExactlyInAnyOrder("payment_paid", "authorized", "closed", "payment_refunded");
  }

  @Test
  void without_a_target_nothing_is_sent_and_nothing_breaks() throws IOException {
    kit.simulator.webhookTarget(dev.mizan.core.payment.Gateway.CARD_GATEWAY, null);
    kit.simulator.stick("order-1", Fault.WEBHOOK_FORGED);

    kit.cardPayment("1111", 15_000, false, "order-1");
    Kit.settle();

    assertThat(kit.webhooks).isEmpty();
  }
}
