package dev.mizan.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.app.support.Api;
import dev.mizan.app.support.Api.Answer;
import dev.mizan.app.support.IntegrationTest;
import dev.mizan.app.support.World;
import dev.mizan.core.payment.GatewayStatus;
import dev.mizan.simulator.Call;
import dev.mizan.simulator.Fault;
import dev.mizan.simulator.SimPayment;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Each answer a gateway can give to a create, one test per row. See docs/lld/payments.md, "Create". */
class CreatePaymentIT extends IntegrationTest {

  @Test
  void paid_gives_a_captured_payment() {
    String order = newOrder();

    Answer created = pay(order, 15_000, "1111", "AUTO");

    assertThat(created.status()).isEqualTo(201);
    assertThat(created.text("status")).isEqualTo("CAPTURED");
    assertThat(created.number("amount")).isEqualTo(15_000);
    assertThat(created.number("captured_amount")).isEqualTo(15_000);
    assertThat(created.number("refunded_amount")).isZero();
    assertThat(created.text("currency")).isEqualTo("SAR");
    assertThat(created.text("reference")).isEqualTo(order);
    assertThat(created.text("method")).isEqualTo("CARD");
    assertThat(created.text("capture")).isEqualTo("AUTO");
    assertThat(created.json().path("card").path("scheme").asString()).isEqualTo("mada");
    assertThat(created.json().path("card").path("last4").asString()).isEqualTo("1111");
    assertThat(created.json().path("next_action").isNull()).isTrue();
    assertThat(created.json().path("pending_reason").isNull()).isTrue();
    assertThat(created.json().path("failure").isNull()).isTrue();
    assertThat(created.json().path("late").asBoolean()).isFalse();
    assertThat(created.text("created_at")).isEqualTo(now().toString());
    String id = created.text("id");
    assertThat(atGateway(id).charges()).isEqualTo(1);
    assertThat(operations(id)).containsExactly("CREATE SUCCEEDED API 15000");
    assertThat(entries(id)).containsExactly("CAPTURE 15000");
    assertThat(events(id)).containsExactly("payment.captured");
    assertThat(column(id, "next_sync_at")).isNull(); // nothing is waiting
  }

  @Test
  void authorized_gives_a_hold_that_ends_after_the_hold_window() {
    Answer created = pay(newOrder(), 15_000, "1111", "MANUAL");

    assertThat(created.status()).isEqualTo(201);
    assertThat(created.text("status")).isEqualTo("AUTHORIZED");
    assertThat(created.text("capture")).isEqualTo("MANUAL");
    assertThat(created.number("captured_amount")).isZero();
    String id = created.text("id");
    assertThat(atGateway(id).holds()).isEqualTo(1);
    assertThat(atGateway(id).charges()).isZero();
    assertThat(entries(id)).isEmpty(); // a hold is not money in the books
    assertThat(events(id)).containsExactly("payment.authorized");
    // The worker looks again when the hold ends, seven days later.
    assertThat(time(id, "hold_ends_at")).isEqualTo(now().plus(Duration.ofDays(7)));
    assertThat(time(id, "next_sync_at")).isEqualTo(time(id, "hold_ends_at"));
  }

  @Test
  void initiated_gives_a_pending_payment_and_the_address_for_the_customer() {
    Answer created = pay(newOrder(), 15_000, "3333", "AUTO");

    assertThat(created.status()).isEqualTo(201);
    assertThat(created.text("status")).isEqualTo("PENDING");
    assertThat(created.text("pending_reason")).isEqualTo("customer_action");
    assertThat(created.json().path("next_action").path("type").asString()).isEqualTo("redirect");
    assertThat(created.json().path("next_action").path("url").asString()).contains("/card/3ds/" + created.text("id"));
    String id = created.text("id");
    assertThat(atGateway(id).charges()).isZero();
    assertThat(operations(id)).containsExactly("CREATE SUCCEEDED API 15000"); // the gateway has the payment
    assertThat(events(id)).isEmpty(); // nothing to tell yet
    assertThat(time(id, "expires_at")).isEqualTo(now().plus(Duration.ofMinutes(30))); // the time for the bank's page
    assertThat(time(id, "next_sync_at")).isEqualTo(now().plusSeconds(15)); // the worker's first look
    assertThat(column(id, "next_sync_at")).isNotNull();
  }

  @Test
  void a_declined_card_gives_a_failed_payment_that_says_why_and_it_is_not_an_error() {
    Answer created = pay(newOrder(), 15_000, "0002", "AUTO");

    assertThat(created.status()).isEqualTo(201);
    assertThat(created.text("status")).isEqualTo("FAILED");
    assertThat(created.json().path("failure").path("code").asString()).isEqualTo("declined");
    assertThat(created.json().path("failure").path("message").asString()).isEqualTo("INSUFFICIENT FUNDS");
    String id = created.text("id");
    assertThat(atGateway(id).charges()).isZero();
    assertThat(entries(id)).isEmpty();
    assertThat(events(id)).containsExactly("payment.failed");
    assertThat(column(id, "next_sync_at")).isNull();
  }

  @Test
  void a_token_the_gateway_does_not_know_gives_a_failed_payment() {
    String order = newOrder();
    Map<String, Object> body = card(order, 15_000, "1111", "AUTO");
    body.put("source", Map.of("token", "tok_made_up"));

    Answer created = api.post("/v1/payments", body);

    assertThat(created.status()).isEqualTo(201);
    assertThat(created.text("status")).isEqualTo("FAILED");
    assertThat(created.json().path("failure").path("code").asString()).isEqualTo("gateway_refused");
    assertThat(operations(created.text("id"))).containsExactly("CREATE FAILED API 15000");
    assertThat(world.simulator.paymentsOf(order)).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(value = Fault.class, names = {"LOST_ANSWER", "ERROR_500_AFTER", "GARBAGE"})
  void an_answer_that_is_lost_after_the_charge_is_found_by_a_fetch(Fault fault) {
    String order = newOrder();
    world.simulator.plan(order, Call.CREATE, fault);

    Answer created = pay(order, 15_000, "1111", "AUTO");

    // The customer was charged and Mizan did not hear. It asked, by its own id, and knows (rule R3).
    assertThat(created.status()).isEqualTo(201);
    assertThat(created.text("status")).isEqualTo("CAPTURED");
    String id = created.text("id");
    assertThat(atGateway(id).charges()).isEqualTo(1);
    assertThat(operations(id)).containsExactly("CREATE SUCCEEDED API 15000");
    assertThat(entries(id)).containsExactly("CAPTURE 15000");
  }

  @ParameterizedTest
  @EnumSource(value = Fault.class, names = {"TIMEOUT_BEFORE", "ERROR_500"})
  void no_answer_and_no_payment_at_the_gateway_leaves_the_result_unknown(Fault fault) {
    String order = newOrder();
    world.simulator.plan(order, Call.CREATE, fault);

    Answer created = pay(order, 15_000, "1111", "AUTO");

    // Not failed: the call may still be on its way. The sync worker decides after the quiet minute.
    assertThat(created.status()).isEqualTo(201);
    assertThat(created.text("status")).isEqualTo("PENDING");
    assertThat(created.text("pending_reason")).isEqualTo("gateway_unknown");
    assertThat(created.json().path("next_action").isNull()).isTrue();
    String id = created.text("id");
    assertThat(operations(id)).containsExactly("CREATE IN_FLIGHT API 15000");
    assertThat(events(id)).isEmpty();
    assertThat(time(id, "next_sync_at")).isEqualTo(now().plusSeconds(60)); // one quiet minute after the call
    assertThat(world.simulator.paymentsOf(order)).isEmpty();
  }

  @Test
  void no_answer_and_a_gateway_that_cannot_be_asked_leaves_the_result_unknown() {
    String order = newOrder();
    world.simulator.plan(order, Call.CREATE, Fault.LOST_ANSWER);
    world.simulator.fetchDown(order, 1);

    Answer created = pay(order, 15_000, "1111", "AUTO");

    assertThat(created.text("status")).isEqualTo("PENDING");
    assertThat(created.text("pending_reason")).isEqualTo("gateway_unknown");
    String id = created.text("id");
    assertThat(atGateway(id).charges()).isEqualTo(1); // the money was taken; Mizan does not know yet

    // The next look finds it. Nothing was sent twice.
    assertThat(sync(id).text("status")).isEqualTo("CAPTURED");
    assertThat(atGateway(id).charges()).isEqualTo(1);
    assertThat(operations(id)).containsExactly("CREATE SUCCEEDED API 15000");
    assertThat(entries(id)).containsExactly("CAPTURE 15000");
    assertThat(events(id)).containsExactly("payment.captured");
  }

  @Test
  void a_slow_gateway_that_charges_after_the_timeout_is_found_by_the_next_look() {
    String order = newOrder();
    world.simulator.plan(order, Call.CREATE, Fault.SLOW_APPLY);

    Answer created = pay(order, 15_000, "1111", "AUTO");
    assertThat(created.text("pending_reason")).isEqualTo("gateway_unknown");
    String id = created.text("id");

    advance(Duration.ofSeconds(10)); // the simulator does the work "a few seconds later"
    assertThat(sync(id).text("status")).isEqualTo("CAPTURED");
    assertThat(atGateway(id).charges()).isEqualTo(1);
  }

  // ---------------------------------------------------------------- pay later

  @Test
  void a_pay_later_payment_starts_pending_with_the_providers_page() {
    String order = newOrder();

    Answer created = api.post("/v1/payments", payLater(order, 15_000, "MANUAL"));

    assertThat(created.status()).isEqualTo(201);
    assertThat(created.text("status")).isEqualTo("PENDING");
    assertThat(created.text("method")).isEqualTo("PAY_LATER");
    assertThat(created.text("pending_reason")).isEqualTo("customer_action");
    assertThat(created.json().path("next_action").path("url").asString()).contains("/paylater/checkout/");
    assertThat(created.json().path("card").isNull()).isTrue();
    String id = created.text("id");
    SimPayment session = world.simulator.paymentsOf(order).get(0);
    assertThat(gatewayId(id)).isEqualTo(session.id); // the provider chose the id
    assertThat(session.mizanPaymentId).isEqualTo(id); // and carries Mizan's as its order reference
    assertThat(session.amount).isEqualTo(15_000);
    assertThat(time(id, "expires_at")).isEqualTo(now().plus(Duration.ofHours(2))); // the time for the provider's page
  }

  @ParameterizedTest
  @EnumSource(value = Fault.class, names = {"LOST_ANSWER", "TIMEOUT_BEFORE", "ERROR_500", "GARBAGE"})
  void a_pay_later_create_with_no_answer_fails_at_once(Fault fault) {
    String order = newOrder();
    world.simulator.plan(order, Call.CREATE, fault);

    Answer created = api.post("/v1/payments", payLater(order, 15_000, "MANUAL"));

    // Mizan has no id to ask about, and nobody can pay a session whose address the customer never got.
    assertThat(created.status()).isEqualTo(201);
    assertThat(created.text("status")).isEqualTo("FAILED");
    assertThat(created.json().path("failure").path("code").asString()).isEqualTo("gateway_unreachable");
    String id = created.text("id");
    assertThat(operations(id)).containsExactly("CREATE FAILED API 15000");
    assertThat(events(id)).containsExactly("payment.failed");
    assertThat(gatewayId(id)).isNull();
    assertThat(world.simulator.paymentsOf(order)).allMatch(session -> session.status() == GatewayStatus.INITIATED && session.charges() == 0);
  }

  // ---------------------------------------------------------------- what goes to the gateway, and what is kept

  @Test
  void the_gateway_gets_mizans_own_id_and_mizans_return_address() {
    String order = newOrder();

    String id = pay(order, 15_000, "3333", "AUTO").text("id");

    assertThat(gatewayId(id)).isEqualTo(id); // rule R3: the id was known before the call
    assertThat(atGateway(id).reference).isEqualTo(order);
    assertThat(atGateway(id).callbackUrl).isEqualTo("https://mizan.example/v1/return/" + id);
    assertThat(column(id, "return_url")).isEqualTo(World.RETURN_PREFIX + "/" + order);
  }

  @Test
  void the_token_is_not_kept_anywhere() {
    String order = newOrder();
    Map<String, Object> body = card(order, 15_000, "1111", "AUTO");
    @SuppressWarnings("unchecked")
    String token = ((Map<String, String>) body.get("source")).get("token");

    String id = api.post("/v1/payments", body).text("id");

    for (String table : List.of("payments", "operations", "idempotency_keys", "outbox_events", "ledger_entries", "discrepancies")) {
      Long rows = jdbc.sql("SELECT count(*) FROM " + table + " t WHERE t::text LIKE ?").param("%" + token + "%").query(Long.class).single();
      assertThat(rows).as("rows of %s that contain the token", table).isZero();
    }
    assertThat(payment(id).raw().body()).doesNotContain(token);
  }

  @Test
  void a_wallet_payment_needs_no_return_address() {
    String order = newOrder();
    String token = world.simulator.createToken("4111110000001111", "visa", true);

    Answer created = api.post("/v1/payments", Api.body(
        "amount", 5_000, "currency", "SAR", "reference", order, "method", "APPLE_PAY", "source", Map.of("token", token)));

    assertThat(created.status()).isEqualTo(201);
    assertThat(created.text("status")).isEqualTo("CAPTURED");
    assertThat(created.text("method")).isEqualTo("APPLE_PAY");
    assertThat(created.text("capture")).isEqualTo("AUTO"); // the default
    assertThat(atGateway(created.text("id")).wallet).isTrue();
  }

  @Test
  void a_description_is_kept_and_shown() {
    String order = newOrder();
    Map<String, Object> body = card(order, 15_000, "1111", "AUTO");
    body.put("description", "Order 1042");

    Answer created = api.post("/v1/payments", body);

    assertThat(created.text("description")).isEqualTo("Order 1042");
    assertThat(payment(created.text("id")).text("description")).isEqualTo("Order 1042");
  }
}
