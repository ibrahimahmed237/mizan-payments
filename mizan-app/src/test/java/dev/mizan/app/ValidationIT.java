package dev.mizan.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.app.support.Api.Answer;
import dev.mizan.app.support.IntegrationTest;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Each rule of the field table of "create a payment", and the one error shape. See docs/lld/api.md. */
class ValidationIT extends IntegrationTest {

  /** A good card payment with one thing changed. */
  private Answer createWith(Consumer<Map<String, Object>> change) {
    Map<String, Object> body = card(newOrder(), 15_000, "1111", "AUTO");
    change.accept(body);
    return api.post("/v1/payments", body);
  }

  private void refused(Answer answer, int status, String code) {
    assertThat(answer.status()).as(answer.raw().body()).isEqualTo(status);
    assertThat(answer.code()).isEqualTo(code);
  }

  @Test
  void an_error_has_one_shape_with_a_code_and_the_requests_id() {
    Answer answer = createWith(body -> body.put("amount", 99));

    assertThat(answer.status()).isEqualTo(422);
    assertThat(answer.header("Content-Type")).startsWith("application/problem+json");
    assertThat(answer.text("type")).isEqualTo("https://mizan.example/problems/invalid_amount");
    assertThat(answer.text("code")).isEqualTo("invalid_amount");
    assertThat(answer.number("status")).isEqualTo(422);
    assertThat(answer.text("title")).contains("100").contains("10000000");
    assertThat(answer.text("request_id")).isEqualTo(answer.header("X-Request-Id")).isNotBlank();
  }

  @Test
  void the_amount_is_from_one_riyal_to_the_limit() {
    refused(createWith(body -> body.put("amount", 99)), 422, "invalid_amount");
    refused(createWith(body -> body.put("amount", 0)), 422, "invalid_amount");
    refused(createWith(body -> body.put("amount", -100)), 422, "invalid_amount");
    refused(createWith(body -> body.put("amount", 10_000_001)), 422, "invalid_amount");
    refused(createWith(body -> body.remove("amount")), 422, "invalid_amount");
    assertThat(createWith(body -> body.put("amount", 100)).status()).isEqualTo(201);
    assertThat(createWith(body -> body.put("amount", 10_000_000)).status()).isEqualTo(201);
  }

  @Test
  void the_amount_is_a_whole_number_not_text_and_not_a_fraction() {
    refused(createWith(body -> body.put("amount", "15000")), 400, "malformed_body");
    refused(createWith(body -> body.put("amount", 150.5)), 400, "malformed_body");
    Answer fraction = createWith(body -> body.put("amount", 150.5));
    assertThat(fraction.text("title")).contains("amount");
  }

  @Test
  void the_currency_is_sar() {
    refused(createWith(body -> body.put("currency", "USD")), 422, "unsupported_currency");
    refused(createWith(body -> body.put("currency", "sar")), 422, "unsupported_currency");
    refused(createWith(body -> body.remove("currency")), 422, "unsupported_currency");
  }

  @Test
  void the_reference_is_1_to_64_safe_characters() {
    refused(createWith(body -> body.remove("reference")), 422, "invalid_reference");
    refused(createWith(body -> body.put("reference", "")), 422, "invalid_reference");
    refused(createWith(body -> body.put("reference", "a".repeat(65))), 422, "invalid_reference");
    refused(createWith(body -> body.put("reference", "order 1")), 422, "invalid_reference");
    refused(createWith(body -> body.put("reference", "order/1")), 422, "invalid_reference");
    refused(createWith(body -> body.put("reference", "طلب")), 422, "invalid_reference");
    assertThat(createWith(body -> body.put("reference", newOrder() + "_A.b-1")).status()).isEqualTo(201);
  }

  @Test
  void the_method_and_the_capture_mode_are_known_words() {
    refused(createWith(body -> body.put("method", "CASH")), 422, "invalid_method");
    refused(createWith(body -> body.put("method", "card")), 422, "invalid_method");
    refused(createWith(body -> body.remove("method")), 422, "invalid_method");
    refused(createWith(body -> body.put("capture", "LATER")), 422, "invalid_capture");
  }

  @Test
  void a_card_needs_a_token_and_pay_later_must_not_have_one() {
    refused(createWith(body -> body.remove("source")), 422, "source_required");
    refused(createWith(body -> body.put("source", Map.of())), 422, "source_required");
    refused(createWith(body -> body.put("source", Map.of("token", " "))), 422, "source_required");
    refused(createWith(body -> body.put("source", Map.of("token", "t".repeat(256)))), 422, "source_required");
    refused(createWith(body -> body.put("method", "PAY_LATER")), 422, "source_not_allowed");
    refused(createWith(body -> {
      body.put("method", "PAY_LATER");
      body.put("source", Map.of());
    }), 422, "source_not_allowed");
  }

  @Test
  void the_return_address_is_needed_and_must_start_with_a_configured_address() {
    refused(createWith(body -> body.remove("return_url")), 422, "return_url_required");
    refused(createWith(body -> body.put("return_url", "https://evil.example/orders/1")), 422, "return_url_not_allowed");
    refused(createWith(body -> body.put("return_url", "https://store.example/ordersX")), 422, "return_url_not_allowed");
    refused(createWith(body -> body.put("return_url", "https://store.example.evil.example/orders/1")), 422, "return_url_not_allowed");
    refused(createWith(body -> body.put("return_url", "javascript:alert(1)")), 422, "return_url_not_allowed");
    refused(createWith(body -> body.put("return_url", "https://store.example/orders/" + "a".repeat(2_100))), 422, "return_url_not_allowed");
    assertThat(createWith(body -> body.put("return_url", "https://store.example/orders?id=1#paid")).status()).isEqualTo(201);
  }

  @Test
  void the_description_is_at_most_255_characters() {
    refused(createWith(body -> body.put("description", "d".repeat(256))), 422, "description_too_long");
    assertThat(createWith(body -> body.put("description", "d".repeat(255))).status()).isEqualTo(201);
  }

  @Test
  void a_field_that_is_not_known_is_refused_by_name() {
    Answer answer = createWith(body -> body.put("amuont", 15_000));

    refused(answer, 400, "malformed_body");
    assertThat(answer.text("title")).contains("amuont");
    refused(createWith(body -> body.put("source", Map.of("token", "t", "card_number", "4111"))), 400, "malformed_body");
  }

  @Test
  void a_body_that_is_not_a_json_object_is_refused() {
    refused(api.post("/v1/payments", "{not json"), 400, "malformed_body");
    refused(api.post("/v1/payments", "[1, 2]"), 400, "malformed_body");
    refused(api.post("/v1/payments", "null"), 400, "malformed_body");
    refused(api.post("/v1/payments", "\"text\""), 400, "malformed_body");
    refused(api.send("POST", "/v1/payments", null, "a-key-for-no-body"), 400, "malformed_body");
  }

  @Test
  void nothing_is_made_by_a_request_that_is_refused() {
    String order = newOrder();
    Map<String, Object> body = card(order, 50, "1111", "AUTO");

    refused(api.post("/v1/payments", body), 422, "invalid_amount");

    assertThat(api.get("/v1/payments?reference=" + order).json().path("data")).isEmpty();
    assertThat(world.simulator.paymentsOf(order)).isEmpty();
  }

  @Test
  void an_operation_has_a_reason_of_at_most_255_characters_and_an_amount_that_is_a_whole_number() {
    String id = captured(newOrder(), 15_000);

    refused(api.post("/v1/payments/" + id + "/refunds", Map.of("reason", "r".repeat(256))), 422, "reason_too_long");
    refused(api.post("/v1/payments/" + id + "/refunds", Map.of("amount", "100")), 400, "malformed_body");
    refused(api.post("/v1/payments/" + id + "/refunds", Map.of("amount", 1.5)), 400, "malformed_body");
    refused(api.post("/v1/payments/" + id + "/refunds", Map.of("amout", 100)), 400, "malformed_body");
    refused(api.post("/v1/payments/" + id + "/refunds", "[]"), 400, "malformed_body");
    assertThat(atGateway(id).refunded()).isZero();
  }

  @Test
  void an_address_or_a_method_that_does_not_exist_has_the_same_error_shape() {
    refused(api.get("/v1/payments/not-a-uuid"), 404, "not_found");
    refused(api.get("/v1/payments/00000000-0000-0000-0000-000000000000"), 404, "not_found");
    refused(api.send("DELETE", "/v1/payments", null, null), 405, "method_not_allowed");
    refused(api.get("/v1/payments?limit=many"), 400, "invalid_parameter");
    refused(api.get("/v1/payments?status=PAID"), 422, "invalid_status");
    refused(api.get("/v1/payments?cursor=nonsense"), 422, "invalid_cursor");
  }
}
