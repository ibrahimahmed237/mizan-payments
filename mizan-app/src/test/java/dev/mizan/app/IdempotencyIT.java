package dev.mizan.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.app.support.Api.Answer;
import dev.mizan.app.support.IntegrationTest;
import dev.mizan.simulator.Call;
import dev.mizan.simulator.Fault;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Each row of the idempotency table. See docs/lld/api.md, "Idempotency". */
class IdempotencyIT extends IntegrationTest {

  private static String newKey() {
    return "key-" + UUID.randomUUID();
  }

  @Test
  void the_same_key_and_body_again_returns_the_same_payment_and_charges_once() {
    String order = newOrder();
    String key = newKey();
    Map<String, Object> body = card(order, 15_000, "1111", "AUTO");

    Answer first = api.post("/v1/payments", body, key);
    Answer again = api.post("/v1/payments", body, key);

    assertThat(first.status()).isEqualTo(201);
    assertThat(first.header("Idempotent-Replay")).isNull();
    assertThat(again.status()).isEqualTo(200);
    assertThat(again.header("Idempotent-Replay")).isEqualTo("true");
    assertThat(again.text("id")).isEqualTo(first.text("id"));
    assertThat(world.simulator.paymentsOf(order)).hasSize(1);
    assertThat(atGateway(first.text("id")).charges()).isEqualTo(1);
    assertThat(entries(first.text("id"))).containsExactly("CAPTURE 15000");
  }

  @Test
  void a_repeat_shows_the_payment_as_it_is_now_not_as_it_was_answered_first() {
    String order = newOrder();
    String key = newKey();
    Map<String, Object> body = card(order, 15_000, "1111", "AUTO");
    String id = api.post("/v1/payments", body, key).text("id");
    api.post("/v1/payments/" + id + "/refunds", Map.of("amount", 4_000));

    Answer again = api.post("/v1/payments", body, key);

    // No answer is stored, only the payment's id. So the repeat cannot show a stale state.
    assertThat(again.number("refunded_amount")).isEqualTo(4_000);
  }

  @Test
  void the_same_body_written_in_another_key_order_is_the_same_request() {
    String key = newKey();
    Map<String, Object> body = card(newOrder(), 15_000, "1111", "AUTO");
    Map<String, Object> reversed = new LinkedHashMap<>();
    List.copyOf(body.keySet()).reversed().forEach(name -> reversed.put(name, body.get(name)));

    Answer first = api.post("/v1/payments", body, key);
    Answer again = api.post("/v1/payments", reversed, key);

    assertThat(again.status()).isEqualTo(200);
    assertThat(again.text("id")).isEqualTo(first.text("id"));
  }

  @Test
  void the_same_key_with_another_body_is_refused() {
    String key = newKey();
    String order = newOrder();
    Answer first = api.post("/v1/payments", card(order, 15_000, "1111", "AUTO"), key);

    Answer other = api.post("/v1/payments", card(order, 16_000, "1111", "AUTO"), key);
    Answer otherOrder = api.post("/v1/payments", card(newOrder(), 15_000, "1111", "AUTO"), key);

    assertThat(first.status()).isEqualTo(201);
    assertThat(other.status()).isEqualTo(422);
    assertThat(other.code()).isEqualTo("idempotency_key_reused");
    assertThat(otherOrder.code()).isEqualTo("idempotency_key_reused");
    assertThat(world.simulator.paymentsOf(order)).hasSize(1);
  }

  @Test
  void a_request_that_was_refused_leaves_the_key_free() {
    String key = newKey();
    String order = newOrder();

    Answer refused = api.post("/v1/payments", card(order, 50, "1111", "AUTO"), key); // too small
    Answer second = api.post("/v1/payments", card(order, 15_000, "1111", "AUTO"), key);

    assertThat(refused.status()).isEqualTo(422);
    assertThat(second.status()).isEqualTo(201); // nothing was made the first time, so the key was not saved
  }

  @Test
  void a_request_refused_for_the_orders_state_leaves_the_key_free_too() {
    String order = newOrder();
    String key = newKey();
    String paid = captured(order, 15_000);
    Map<String, Object> second = card(order, 15_000, "1111", "AUTO");

    Answer refused = api.post("/v1/payments", second, key);
    assertThat(refused.status()).isEqualTo(409);
    api.post("/v1/payments/" + paid + "/refunds", Map.of()); // the order is no longer paid

    assertThat(api.post("/v1/payments", second, key).status()).isEqualTo(201);
  }

  @Test
  void a_post_that_moves_money_needs_a_key() {
    String id = authorized(newOrder(), 15_000);

    for (String path : List.of("/v1/payments", "/v1/payments/" + id + "/capture", "/v1/payments/" + id + "/cancel", "/v1/payments/" + id + "/refunds")) {
      Answer answer = api.send("POST", path, card(newOrder(), 15_000, "1111", "AUTO"), null);
      assertThat(answer.status()).as(path).isEqualTo(400);
      assertThat(answer.code()).isEqualTo("idempotency_key_required");
    }
    assertThat(payment(id).text("status")).isEqualTo("AUTHORIZED");
  }

  @Test
  void a_key_is_1_to_128_printable_characters() {
    Map<String, Object> body = card(newOrder(), 15_000, "1111", "AUTO");

    assertThat(api.post("/v1/payments", body, "k".repeat(129)).code()).isEqualTo("invalid_idempotency_key");
    assertThat(api.post("/v1/payments", body, "has space").code()).isEqualTo("invalid_idempotency_key");
    assertThat(api.post("/v1/payments", body, "k".repeat(128)).status()).isEqualTo(201);
  }

  @Test
  void a_repeat_of_an_operation_returns_the_same_operation_and_refunds_once() {
    String id = captured(newOrder(), 15_000);
    String key = newKey();

    Answer first = api.post("/v1/payments/" + id + "/refunds", Map.of("amount", 5_000), key);
    Answer again = api.post("/v1/payments/" + id + "/refunds", Map.of("amount", 5_000), key);

    assertThat(first.status()).isEqualTo(201);
    assertThat(again.status()).isEqualTo(200);
    assertThat(again.header("Idempotent-Replay")).isEqualTo("true");
    assertThat(again.text("id")).isEqualTo(first.text("id"));
    assertThat(atGateway(id).refunded()).isEqualTo(5_000);
    assertThat(payment(id).number("refunded_amount")).isEqualTo(5_000);
  }

  @Test
  void a_repeat_of_an_operation_with_another_amount_or_on_another_payment_is_refused() {
    String id = captured(newOrder(), 15_000);
    String other = captured(newOrder(), 15_000);
    String key = newKey();
    api.post("/v1/payments/" + id + "/refunds", Map.of("amount", 5_000), key);

    assertThat(api.post("/v1/payments/" + id + "/refunds", Map.of("amount", 6_000), key).code()).isEqualTo("idempotency_key_reused");
    assertThat(api.post("/v1/payments/" + other + "/refunds", Map.of("amount", 5_000), key).code()).isEqualTo("idempotency_key_reused");
    assertThat(atGateway(other).refunded()).isZero();
  }

  @Test
  void a_key_of_a_payment_cannot_be_used_for_an_operation_and_the_other_way_round() {
    String key = newKey();
    Map<String, Object> body = card(newOrder(), 15_000, "1111", "AUTO");
    String id = api.post("/v1/payments", body, key).text("id");
    String refundKey = newKey();
    api.post("/v1/payments/" + id + "/refunds", Map.of("amount", 1_000), refundKey);

    assertThat(api.post("/v1/payments/" + id + "/refunds", Map.of("amount", 1_000), key).code()).isEqualTo("idempotency_key_reused");
    assertThat(api.post("/v1/payments", body, refundKey).code()).isEqualTo("idempotency_key_reused");
    assertThat(atGateway(id).refunded()).isEqualTo(1_000);
  }

  @Test
  void a_repeat_after_a_lost_answer_does_not_charge_again() {
    String order = newOrder();
    String key = newKey();
    Map<String, Object> body = card(order, 15_000, "1111", "AUTO");
    world.simulator.plan(order, Call.CREATE, Fault.TIMEOUT_BEFORE);

    Answer first = api.post("/v1/payments", body, key); // the gateway never got it
    Answer again = api.post("/v1/payments", body, key); // the store tries again

    // The repeat does not send a second create. It shows the same payment, still waiting for its result.
    assertThat(first.text("pending_reason")).isEqualTo("gateway_unknown");
    assertThat(again.status()).isEqualTo(200);
    assertThat(again.text("id")).isEqualTo(first.text("id"));
    assertThat(again.text("pending_reason")).isEqualTo("gateway_unknown");
    assertThat(world.simulator.paymentsOf(order)).isEmpty();
  }

  @Test
  void a_key_is_free_again_after_30_days() {
    String key = newKey();
    String order = newOrder();
    api.post("/v1/payments", card(order, 15_000, "0002", "AUTO"), key); // declined

    advance(Duration.ofDays(30).minusSeconds(1));
    assertThat(api.post("/v1/payments", card(order, 16_000, "1111", "AUTO"), key).code()).isEqualTo("idempotency_key_reused");

    advance(Duration.ofSeconds(1));
    assertThat(api.post("/v1/payments", card(order, 16_000, "1111", "AUTO"), key).status()).isEqualTo(201);
  }
}
