package dev.mizan.app.adapter.out.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.mizan.app.application.port.GatewayClient;
import dev.mizan.app.application.port.GatewayClient.Create;
import dev.mizan.app.application.port.GatewayResult.Done;
import dev.mizan.app.application.port.GatewayResult.Refused;
import dev.mizan.app.application.port.GatewayResult.Unknown;
import dev.mizan.core.payment.Gateway;
import dev.mizan.core.payment.Method;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the clients put on the wire, and how they read each kind of HTTP answer. A stub server records every request
 * and answers what the test tells it to.
 */
class GatewayWireTest {

  /** One request as it arrived. */
  private record Seen(String method, String path, String authorization, String contentType, String body) {
    JsonNode json() {
      return JsonMapper.builder().build().readTree(body);
    }
  }

  private static final String ID = "11111111-2222-3333-4444-555555555555";

  private final List<Seen> seen = new ArrayList<>();
  private final HttpServer stub;
  private volatile int status = 200;
  private volatile String answer = "{}";
  private volatile long delayMillis;

  private final GatewayClient card;
  private final GatewayClient payLater;

  GatewayWireTest() throws IOException {
    stub = HttpServer.create(new InetSocketAddress(0), 0);
    stub.createContext("/", exchange -> {
      try (exchange) {
        seen.add(new Seen(
            exchange.getRequestMethod(), exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("Authorization"),
            exchange.getRequestHeaders().getFirst("Content-Type"), new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        if (delayMillis > 0) {
          Thread.sleep(delayMillis);
        }
        byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        exchange.getResponseBody().write(bytes);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    stub.start();
    String url = "http://localhost:" + stub.getAddress().getPort();
    var gateways = new HttpGateways(
        new HttpGateways.Access(url + "/card", "sk_secret"), new HttpGateways.Access(url + "/later", "pl_secret"),
        Duration.ofSeconds(2), Duration.ofMillis(300));
    card = gateways.of(Gateway.CARD_GATEWAY);
    payLater = gateways.of(Gateway.PAY_LATER);
  }

  @AfterEach
  void stop() {
    stub.stop(0);
  }

  private static Create create(Method method, String token) {
    return new Create(UUID.fromString(ID), "order-7", 15_000, "SAR", method, token, true, "https://mizan.example/v1/return/" + ID, "Order 7");
  }

  private void answers(int httpStatus, String body) {
    status = httpStatus;
    answer = body.replace('\'', '"');
  }

  // ---------------------------------------------------------------- what is sent

  @Test
  void a_card_create_sends_mizans_id_the_amount_in_halalas_and_the_token() {
    answers(201, "{'id':'" + ID + "','status':'authorized','amount':15000,'currency':'SAR'}");

    card.create(create(Method.CARD, "tok_abc"));

    Seen request = seen.get(0);
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.path()).isEqualTo("/card/payments");
    assertThat(request.authorization()).isEqualTo("Basic c2tfc2VjcmV0Og=="); // "sk_secret:" in Base64: the key is the user name
    assertThat(request.contentType()).isEqualTo("application/json");
    JsonNode body = request.json();
    assertThat(body.path("given_id").asString()).isEqualTo(ID);
    assertThat(body.path("amount").isIntegralNumber()).isTrue();
    assertThat(body.path("amount").asLong()).isEqualTo(15_000);
    assertThat(body.path("currency").asString()).isEqualTo("SAR");
    assertThat(body.path("manual").asBoolean()).isTrue();
    assertThat(body.path("callback_url").asString()).isEqualTo("https://mizan.example/v1/return/" + ID);
    assertThat(body.path("description").asString()).isEqualTo("Order 7");
    assertThat(body.path("source").path("type").asString()).isEqualTo("token");
    assertThat(body.path("source").path("token").asString()).isEqualTo("tok_abc");
    assertThat(body.path("metadata").path("reference").asString()).isEqualTo("order-7");
    assertThat(body.path("metadata").path("mizan_payment_id").asString()).isEqualTo(ID);
  }

  @Test
  void the_card_operations_use_the_gateways_paths_and_send_halalas() {
    answers(200, "{'id':'" + ID + "','status':'captured','amount':15000,'currency':'SAR','captured':15000}");

    card.fetch(ID);
    card.capture(ID, 10_000);
    card.cancel(ID);
    card.refund(ID, 2_500, "a-key-this-gateway-does-not-take");

    assertThat(seen).extracting(Seen::method).containsExactly("GET", "POST", "POST", "POST");
    assertThat(seen).extracting(Seen::path).containsExactly(
        "/card/payments/" + ID, "/card/payments/" + ID + "/capture", "/card/payments/" + ID + "/void", "/card/payments/" + ID + "/refund");
    assertThat(seen.get(0).body()).isEmpty();
    assertThat(seen.get(1).json().path("amount").asLong()).isEqualTo(10_000);
    assertThat(seen.get(3).json().path("amount").asLong()).isEqualTo(2_500);
    assertThat(seen.get(3).body()).doesNotContain("a-key");
  }

  @Test
  void a_pay_later_create_sends_riyals_as_text_and_mizans_id_as_the_order_reference() {
    answers(200, "{'payment':{'id':'pl_1','status':'CREATED','amount':'150.00','currency':'SAR'},"
        + "'configuration':{'available_products':{'installments':[{'web_url':'https://later.example/pay/pl_1'}]}}}");

    Done done = (Done) payLater.create(create(Method.PAY_LATER, null));

    Seen request = seen.get(0);
    assertThat(request.path()).isEqualTo("/later/checkout");
    assertThat(request.authorization()).isEqualTo("Bearer pl_secret");
    JsonNode body = request.json();
    assertThat(body.path("payment").path("amount").isString()).isTrue();
    assertThat(body.path("payment").path("amount").asString()).isEqualTo("150.00");
    assertThat(body.path("payment").path("order").path("reference_id").asString()).isEqualTo(ID);
    assertThat(body.path("payment").path("meta").path("order_id").asString()).isEqualTo("order-7");
    assertThat(body.path("merchant_urls").path("success").asString()).isEqualTo("https://mizan.example/v1/return/" + ID);
    assertThat(done.gatewayPaymentId()).isEqualTo("pl_1");
    assertThat(done.snapshot().actionUrl()).isEqualTo("https://later.example/pay/pl_1");
  }

  @Test
  void the_pay_later_operations_use_the_providers_paths_and_the_refund_carries_its_key() {
    answers(200, "{'id':'pl_1','status':'CLOSED','amount':'150.00','currency':'SAR','captures':[{'amount':'150.00'}]}");

    payLater.fetch("pl_1");
    payLater.capture("pl_1", 15_000);
    payLater.cancel("pl_1");
    payLater.refund("pl_1", 2_550, "refund-key-1");

    assertThat(seen).extracting(Seen::path).containsExactly(
        "/later/payments/pl_1", "/later/payments/pl_1/captures", "/later/payments/pl_1/close", "/later/payments/pl_1/refunds");
    assertThat(seen.get(1).json().path("amount").asString()).isEqualTo("150.00");
    assertThat(seen.get(3).json().path("amount").asString()).isEqualTo("25.50");
    assertThat(seen.get(3).json().path("reference_id").asString()).isEqualTo("refund-key-1");
  }

  // ---------------------------------------------------------------- how answers are read

  @ParameterizedTest
  @ValueSource(ints = {400, 402, 404, 409, 422})
  void a_clear_no_to_an_operation_is_refused_with_the_gateways_message(int httpStatus) {
    answers(httpStatus, "{'type':'invalid_request_error','message':'The refund is more than what is left.'}");

    assertThat(card.refund(ID, 100, "k")).isEqualTo(new Refused("gateway_refused", "The refund is more than what is left."));
    assertThat(payLater.capture("pl_1", 100)).isEqualTo(new Refused("gateway_refused", "The refund is more than what is left."));
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 403, 408, 429, 500, 502, 503, 504, 301})
  void an_answer_that_says_nothing_about_the_payment_is_unknown(int httpStatus) {
    answers(httpStatus, "{'message':'no'}");

    assertThat(card.capture(ID, 100)).isEqualTo(new Unknown("http_" + httpStatus));
    assertThat(payLater.capture("pl_1", 100)).isEqualTo(new Unknown("http_" + httpStatus));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "not json at all", "", "[]", "{}", "{'id':'another','status':'paid','amount':15000,'currency':'SAR'}",
      "{'id':'" + ID + "','status':'paid','amount':'15000','currency':'SAR'}"})
  void a_success_with_a_body_that_is_not_this_payments_record_is_unknown(String body) {
    answers(200, body);

    assertThat(card.capture(ID, 100)).isEqualTo(new Unknown("unreadable_answer"));
    assertThat(card.create(create(Method.CARD, "tok"))).isEqualTo(new Unknown("unreadable_answer"));
    assertThat(payLater.fetch(ID)).isEqualTo(new Unknown("unreadable_answer"));
    assertThat(payLater.create(create(Method.PAY_LATER, null))).isEqualTo(new Unknown("unreadable_answer"));
  }

  @Test
  void an_answer_that_comes_too_late_is_unknown_and_the_call_is_sent_only_once() {
    answers(200, "{'id':'" + ID + "','status':'paid','amount':15000,'currency':'SAR'}");
    delayMillis = 800;

    assertThat(card.refund(ID, 100, "k")).isEqualTo(new Unknown("no_answer"));
    assertThat(seen).hasSize(1); // the client never sends again by itself
  }

  @Test
  void a_server_that_is_not_there_is_unknown() {
    stub.stop(0);

    assertThat(card.fetch(ID)).isEqualTo(new Unknown("no_answer"));
    assertThat(payLater.create(create(Method.PAY_LATER, null))).isEqualTo(new Unknown("no_answer"));
  }

  @Test
  void not_found_means_no_such_payment_only_for_a_fetch() {
    answers(404, "{'message':'not found'}");

    assertThat(((Done) card.fetch(ID)).snapshot().status().name()).isEqualTo("NOT_FOUND");
    assertThat(((Done) payLater.fetch("pl_1")).snapshot().status().name()).isEqualTo("NOT_FOUND");
    assertThat(card.capture(ID, 100)).isInstanceOf(Refused.class);
  }

  @Test
  void a_create_answered_with_this_id_exists_is_followed_by_a_fetch_of_that_payment() {
    answers(409, "{'message':'exists'}");

    card.create(create(Method.CARD, "tok"));

    assertThat(seen).extracting(Seen::method).containsExactly("POST", "GET");
    assertThat(seen.get(1).path()).isEqualTo("/card/payments/" + ID);
  }

  @Test
  void a_refusal_with_a_very_long_or_missing_message_is_still_a_refusal() {
    answers(400, "{'message':'" + "x".repeat(1_000) + "'}");
    assertThat(((Refused) card.capture(ID, 100)).message()).hasSize(255);

    answers(400, "<html>Bad Request</html>");
    assertThat(card.capture(ID, 100)).isEqualTo(new Refused("gateway_refused", null));
  }
}
