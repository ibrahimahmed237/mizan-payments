package dev.mizan.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.core.payment.GatewayStatus;
import dev.mizan.simulator.Kit.Answer;
import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CardGatewayTest {

  private final Kit kit = new Kit();

  @AfterEach
  void stop() {
    kit.close();
  }

  @Test
  void a_card_that_needs_no_code_is_paid_at_once() throws IOException {
    String id = Kit.newId();

    Answer created = kit.card("POST", "/payments", Kit.createBody(id, 15_000, kit.token("1111"), false, "order-1"));

    assertThat(created.status()).isEqualTo(201);
    assertThat(created.json().path("id").asString()).isEqualTo(id);
    assertThat(created.json().path("status").asString()).isEqualTo("paid");
    assertThat(created.json().path("amount").asLong()).isEqualTo(15_000);
    assertThat(created.json().path("captured").asLong()).isEqualTo(15_000);
    assertThat(created.json().path("source").path("company").asString()).isEqualTo("mada");
    assertThat(created.json().path("source").path("number").asString()).isEqualTo("XXXX-XXXX-XXXX-1111");
    assertThat(kit.card("GET", "/payments/" + id, null).json()).isEqualTo(created.json());
    assertThat(kit.simulator.payment(id).charges()).isEqualTo(1);
  }

  @Test
  void the_same_id_again_returns_the_same_payment_and_charges_nothing() throws IOException {
    String id = kit.cardPayment("1111", 15_000, false, "order-1");

    Answer again = kit.card("POST", "/payments", Kit.createBody(id, 15_000, "tok_used_or_missing", false, "order-1"));

    assertThat(again.status()).isEqualTo(201);
    assertThat(again.json().path("status").asString()).isEqualTo("paid");
    assertThat(kit.simulator.payment(id).charges()).isEqualTo(1);
    assertThat(kit.simulator.payments()).hasSize(1);
  }

  @Test
  void a_token_works_once() throws IOException {
    String token = kit.token("1111");
    kit.card("POST", "/payments", Kit.createBody(Kit.newId(), 1_000, token, false, "order-1"));

    Answer second = kit.card("POST", "/payments", Kit.createBody(Kit.newId(), 1_000, token, false, "order-2"));

    assertThat(second.status()).isEqualTo(400);
    assertThat(second.json().path("type").asString()).isEqualTo("invalid_request_error");
    assertThat(kit.simulator.payments()).hasSize(1);
  }

  @Test
  void a_card_that_needs_the_banks_code_waits_for_the_customer() throws IOException {
    String id = kit.cardPayment("3333", 15_000, false, "order-1");
    Answer waiting = kit.card("GET", "/payments/" + id, null);

    URI back = kit.simulator.customerApproves(id);

    assertThat(waiting.json().path("status").asString()).isEqualTo("initiated");
    assertThat(waiting.json().path("source").path("transaction_url").asString()).isEqualTo(kit.server.baseUrl() + "/card/3ds/" + id);
    assertThat(back.toString()).isEqualTo(Kit.RETURN_URL + "?id=" + id + "&status=paid");
    assertThat(kit.card("GET", "/payments/" + id, null).json().path("status").asString()).isEqualTo("paid");
    assertThat(kit.card("GET", "/payments/" + id, null).json().path("source").has("transaction_url")).isFalse();
  }

  @Test
  void the_banks_page_works_over_http_too() throws IOException {
    String id = kit.cardPayment("3333", 15_000, false, "order-1");

    Answer page = kit.send("GET", "/card/3ds/" + id, null, null);
    Answer approved = kit.send("POST", "/card/3ds/" + id + "?result=approve", null, null);

    assertThat(page.status()).isEqualTo(200);
    assertThat(page.text()).contains("Approve");
    assertThat(approved.status()).isEqualTo(302);
    assertThat(kit.simulator.payment(id).status()).isEqualTo(GatewayStatus.PAID);
  }

  @Test
  void a_customer_who_fails_the_banks_code_fails_the_payment() throws IOException {
    String id = kit.cardPayment("3333", 15_000, false, "order-1");

    URI back = kit.simulator.customerDeclines(id);

    assertThat(back.toString()).endsWith("&status=failed");
    assertThat(kit.card("GET", "/payments/" + id, null).json().path("source").path("message").asString()).isEqualTo("3-D SECURE FAILED");
    assertThat(kit.simulator.payment(id).charges()).isZero();
  }

  @Test
  void the_banks_page_can_refuse_too() throws IOException {
    String id = kit.cardPayment("3333", 15_000, false, "order-1");

    Answer declined = kit.send("POST", "/card/3ds/" + id + "?result=decline", null, null);

    assertThat(declined.status()).isEqualTo(302);
    assertThat(kit.simulator.payment(id).status()).isEqualTo(GatewayStatus.FAILED);
    assertThat(kit.simulator.payment(id).charges()).isZero();
  }

  @Test
  void approving_twice_charges_once() throws IOException {
    String id = kit.cardPayment("3333", 15_000, false, "order-1");

    kit.simulator.customerApproves(id);
    kit.simulator.customerApproves(id);

    assertThat(kit.simulator.payment(id).charges()).isEqualTo(1);
  }

  @ParameterizedTest
  @CsvSource({"0002,INSUFFICIENT FUNDS", "0069,EXPIRED CARD"})
  void test_cards_are_declined_with_a_reason(String last4, String message) throws IOException {
    String id = kit.cardPayment(last4, 15_000, false, "order-1");

    Answer payment = kit.card("GET", "/payments/" + id, null);

    assertThat(payment.json().path("status").asString()).isEqualTo("failed");
    assertThat(payment.json().path("source").path("message").asString()).isEqualTo(message);
    assertThat(kit.simulator.payment(id).charges()).isZero();
  }

  @Test
  void a_wallet_token_is_paid_without_the_customer_even_for_a_card_that_needs_a_code() throws IOException {
    String id = Kit.newId();

    Answer created = kit.card("POST", "/payments", Kit.createBody(id, 15_000, kit.walletToken("3333"), false, "order-1"));

    assertThat(created.json().path("status").asString()).isEqualTo("paid");
    assertThat(created.json().path("source").path("type").asString()).isEqualTo("applepay");
  }

  @Test
  void a_manual_payment_is_held_then_captured_in_part_once() throws IOException {
    String id = kit.cardPayment("1111", 15_000, true, "order-1");
    Answer held = kit.card("GET", "/payments/" + id, null);

    Answer captured = kit.card("POST", "/payments/" + id + "/capture", "{\"amount\":9000}");
    Answer again = kit.card("POST", "/payments/" + id + "/capture", "{\"amount\":1000}");

    assertThat(held.json().path("status").asString()).isEqualTo("authorized");
    assertThat(kit.simulator.payment(id).holds()).isEqualTo(1);
    assertThat(captured.status()).isEqualTo(200);
    assertThat(captured.json().path("status").asString()).isEqualTo("captured");
    assertThat(captured.json().path("captured").asLong()).isEqualTo(9_000);
    assertThat(again.status()).isEqualTo(400);
    assertThat(kit.simulator.payment(id).charges()).isEqualTo(1);
  }

  @Test
  void a_capture_without_an_amount_takes_all_and_a_wrong_amount_is_refused() throws IOException {
    String full = kit.cardPayment("1111", 15_000, true, "order-1");
    String wrong = kit.cardPayment("1111", 15_000, true, "order-2");

    assertThat(kit.card("POST", "/payments/" + full + "/capture", "{}").json().path("captured").asLong()).isEqualTo(15_000);
    assertThat(kit.card("POST", "/payments/" + wrong + "/capture", "{\"amount\":15001}").status()).isEqualTo(400);
    assertThat(kit.card("POST", "/payments/" + wrong + "/capture", "{\"amount\":0}").status()).isEqualTo(400);
  }

  @Test
  void a_hold_can_be_voided_and_then_nothing_else_works() throws IOException {
    String id = kit.cardPayment("1111", 15_000, true, "order-1");

    Answer voided = kit.card("POST", "/payments/" + id + "/void", "{}");

    assertThat(voided.json().path("status").asString()).isEqualTo("voided");
    assertThat(kit.card("POST", "/payments/" + id + "/capture", "{}").status()).isEqualTo(400);
    assertThat(kit.card("POST", "/payments/" + id + "/void", "{}").status()).isEqualTo(400);
    assertThat(kit.card("POST", "/payments/" + id + "/refund", "{}").status()).isEqualTo(400);
  }

  @Test
  void a_void_after_capture_shows_voided_with_the_captured_amount_and_gives_the_money_back() throws IOException {
    String id = kit.cardPayment("1111", 15_000, false, "order-1");

    Answer voided = kit.card("POST", "/payments/" + id + "/void", "{}");

    assertThat(voided.json().path("status").asString()).isEqualTo("voided");
    assertThat(voided.json().path("captured").asLong()).isEqualTo(15_000);
    assertThat(kit.simulator.payment(id).refunded()).isEqualTo(15_000);
    assertThat(kit.card("POST", "/payments/" + id + "/refund", "{\"amount\":1}").status()).isEqualTo(400);
  }

  @Test
  void refunds_add_up_and_cannot_pass_what_was_captured() throws IOException {
    String id = kit.cardPayment("1111", 15_000, false, "order-1");

    Answer first = kit.card("POST", "/payments/" + id + "/refund", "{\"amount\":5000}");
    Answer tooMuch = kit.card("POST", "/payments/" + id + "/refund", "{\"amount\":10001}");
    Answer rest = kit.card("POST", "/payments/" + id + "/refund", "{}");

    assertThat(first.json().path("status").asString()).isEqualTo("refunded");
    assertThat(first.json().path("refunded").asLong()).isEqualTo(5_000);
    assertThat(tooMuch.status()).isEqualTo(400);
    assertThat(rest.json().path("refunded").asLong()).isEqualTo(15_000);
    assertThat(kit.card("POST", "/payments/" + id + "/refund", "{\"amount\":1}").status()).isEqualTo(400);
  }

  @Test
  void a_payment_that_is_not_paid_cannot_be_refunded() throws IOException {
    String id = kit.cardPayment("3333", 15_000, false, "order-1");

    assertThat(kit.card("POST", "/payments/" + id + "/refund", "{\"amount\":1}").status()).isEqualTo(400);
  }

  @Test
  void wrong_requests_are_refused() throws IOException {
    String token = kit.token("1111");

    assertThat(kit.card("POST", "/payments", Kit.createBody("not-a-uuid", 1_000, token, false, "o")).status()).isEqualTo(400);
    assertThat(kit.card("POST", "/payments", Kit.createBody(Kit.newId(), 99, token, false, "o")).status()).isEqualTo(400);
    assertThat(kit.card("POST", "/payments", "{not json").status()).isEqualTo(400);
    assertThat(kit.card("POST", "/tokens", "{\"number\":\"123\"}").status()).isEqualTo(400);
    assertThat(kit.card("GET", "/payments/" + Kit.newId(), null).status()).isEqualTo(404);
    assertThat(kit.card("POST", "/payments/" + Kit.newId() + "/refund", "{}").status()).isEqualTo(404);
    assertThat(kit.card("GET", "/nothing/here", null).status()).isEqualTo(404);
    assertThat(kit.simulator.payments()).isEmpty();
  }

  @Test
  void a_wrong_key_is_not_authorized() throws IOException {
    assertThat(kit.send("GET", "/card/v1/payments/" + Kit.newId(), null, "Basic d3Jvbmc6").status()).isEqualTo(401);
    assertThat(kit.send("GET", "/card/v1/payments/" + Kit.newId(), null, null).status()).isEqualTo(401);
  }
}
