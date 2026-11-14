package dev.mizan.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.core.payment.GatewayStatus;
import dev.mizan.simulator.Kit.Answer;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PayLaterTest {

  private final Kit kit = new Kit();

  @AfterEach
  void stop() {
    kit.close();
  }

  @Test
  void a_session_gives_the_providers_page_and_nothing_is_paid_yet() throws IOException {
    String mizanId = Kit.newId();

    Answer session = kit.payLater("POST", "/checkout", Kit.checkoutBody("150.00", mizanId, "order-1"));
    String id = session.json().path("payment").path("id").asString();

    assertThat(session.status()).isEqualTo(200);
    assertThat(session.json().path("payment").path("status").asString()).isEqualTo("CREATED");
    assertThat(session.json().path("payment").path("amount").asString()).isEqualTo("150.00");
    assertThat(session.json().path("payment").path("order").path("reference_id").asString()).isEqualTo(mizanId);
    assertThat(session.json().path("payment").path("meta").path("order_id").asString()).isEqualTo("order-1");
    assertThat(session.json().path("configuration").path("available_products").path("installments").get(0).path("web_url").asString())
        .isEqualTo(kit.server.baseUrl() + "/paylater/checkout/" + id);
    assertThat(kit.simulator.payment(id).charges()).isZero();
  }

  @Test
  void the_customer_approves_then_the_store_captures_in_full_and_the_payment_closes() throws IOException {
    String id = kit.payLaterPayment("150.00", "order-1");

    kit.simulator.customerApproves(id);
    Answer authorized = kit.payLater("GET", "/payments/" + id, null);
    Answer captured = kit.payLater("POST", "/payments/" + id + "/captures", "{\"amount\":\"150.00\"}");

    assertThat(authorized.json().path("status").asString()).isEqualTo("AUTHORIZED");
    assertThat(authorized.json().path("captures").isEmpty()).isTrue();
    assertThat(captured.json().path("status").asString()).isEqualTo("CLOSED");
    assertThat(captured.json().path("captures").get(0).path("amount").asString()).isEqualTo("150.00");
    assertThat(kit.simulator.payment(id).captured()).isEqualTo(15_000);
    assertThat(kit.simulator.payment(id).charges()).isEqualTo(1);
  }

  @Test
  void the_provider_does_not_capture_by_itself() throws IOException {
    String id = kit.payLaterPayment("150.00", "order-1");

    kit.simulator.customerApproves(id);

    assertThat(kit.simulator.payment(id).status()).isEqualTo(GatewayStatus.AUTHORIZED);
    assertThat(kit.simulator.payment(id).captured()).isZero();
  }

  @Test
  void a_part_capture_and_a_capture_before_approval_are_refused() throws IOException {
    String id = kit.payLaterPayment("150.00", "order-1");

    Answer early = kit.payLater("POST", "/payments/" + id + "/captures", "{\"amount\":\"150.00\"}");
    kit.simulator.customerApproves(id);
    Answer part = kit.payLater("POST", "/payments/" + id + "/captures", "{\"amount\":\"100.00\"}");

    assertThat(early.status()).isEqualTo(400);
    assertThat(part.status()).isEqualTo(400);
    assertThat(kit.simulator.payment(id).captured()).isZero();
  }

  @Test
  void closing_a_held_payment_releases_it() throws IOException {
    String id = kit.payLaterPayment("150.00", "order-1");
    kit.simulator.customerApproves(id);

    Answer closed = kit.payLater("POST", "/payments/" + id + "/close", "{}");

    assertThat(closed.json().path("status").asString()).isEqualTo("CLOSED");
    assertThat(closed.json().path("captures").isEmpty()).isTrue();
    assertThat(kit.simulator.payment(id).status()).isEqualTo(GatewayStatus.VOIDED);
    assertThat(kit.payLater("POST", "/payments/" + id + "/close", "{}").status()).isEqualTo(400);
  }

  @Test
  void a_refund_with_the_same_key_is_done_once() throws IOException {
    String id = kit.payLaterPayment("150.00", "order-1");
    kit.simulator.customerApproves(id);
    kit.payLater("POST", "/payments/" + id + "/captures", "{\"amount\":\"150.00\"}");

    Answer first = kit.payLater("POST", "/payments/" + id + "/refunds", "{\"amount\":\"50.00\",\"reference_id\":\"refund-1\"}");
    Answer repeat = kit.payLater("POST", "/payments/" + id + "/refunds", "{\"amount\":\"50.00\",\"reference_id\":\"refund-1\"}");
    Answer other = kit.payLater("POST", "/payments/" + id + "/refunds", "{\"amount\":\"100.00\",\"reference_id\":\"refund-2\"}");
    Answer tooMuch = kit.payLater("POST", "/payments/" + id + "/refunds", "{\"amount\":\"0.01\",\"reference_id\":\"refund-3\"}");

    assertThat(first.json().path("refunds").size()).isEqualTo(1);
    assertThat(repeat.status()).isEqualTo(200);
    assertThat(repeat.json().path("refunds").size()).isEqualTo(1);
    assertThat(other.json().path("refunds").size()).isEqualTo(2);
    assertThat(tooMuch.status()).isEqualTo(400);
    assertThat(kit.simulator.payment(id).refunded()).isEqualTo(15_000);
  }

  @Test
  void the_customer_can_be_rejected_or_leave() throws IOException {
    String rejected = kit.payLaterPayment("150.00", "order-1");
    String left = kit.payLaterPayment("150.00", "order-2");

    kit.simulator.customerDeclines(rejected);
    kit.simulator.customerLeaves(left);

    assertThat(kit.payLater("GET", "/payments/" + rejected, null).json().path("status").asString()).isEqualTo("REJECTED");
    assertThat(kit.payLater("GET", "/payments/" + left, null).json().path("status").asString()).isEqualTo("EXPIRED");
    assertThat(kit.simulator.customerApproves(left).toString()).endsWith("&status=EXPIRED");
    assertThat(kit.simulator.payment(left).status()).isEqualTo(GatewayStatus.EXPIRED);
  }

  @Test
  void wrong_requests_are_refused() throws IOException {
    assertThat(kit.payLater("POST", "/checkout", Kit.checkoutBody("0.50", Kit.newId(), "o")).status()).isEqualTo(400);
    assertThat(kit.payLater("POST", "/checkout", Kit.checkoutBody("1.505", Kit.newId(), "o")).status()).isEqualTo(400);
    assertThat(kit.payLater("GET", "/payments/pl_nothing", null).status()).isEqualTo(404);
    assertThat(kit.send("GET", "/paylater/v2/payments/pl_1", null, "Bearer wrong").status()).isEqualTo(401);
    assertThat(kit.simulator.payments()).isEmpty();
  }

  @Test
  void a_card_payment_is_not_a_pay_later_payment() throws IOException {
    String card = kit.cardPayment("1111", 15_000, false, "order-1");

    assertThat(kit.payLater("GET", "/payments/" + card, null).status()).isEqualTo(404);
    assertThat(kit.payLater("POST", "/payments/" + card + "/refunds", "{\"amount\":\"1.00\"}").status()).isEqualTo(404);
  }
}
