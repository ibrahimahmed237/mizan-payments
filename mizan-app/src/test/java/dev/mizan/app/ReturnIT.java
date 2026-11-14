package dev.mizan.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.app.support.Api;
import dev.mizan.app.support.Api.Answer;
import dev.mizan.app.support.IntegrationTest;
import dev.mizan.app.support.World;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The customer comes back from the bank's page or the provider's page. See docs/lld/workers.md. */
class ReturnIT extends IntegrationTest {

  /** A browser: no API key, and it does not follow the redirect, so the test can read it. */
  private final Api browser = api.withKey(null);

  private Answer back(String id) {
    return browser.get("/v1/return/" + id);
  }

  @Test
  void the_customer_is_sent_on_to_the_store_and_the_payment_is_fresh_by_then() {
    String order = newOrder();
    String id = pay(order, 15_000, "3333", "AUTO").text("id");
    advance(Duration.ofSeconds(40)); // the customer is on the bank's page
    world.simulator.customerApproves(id);

    Answer answer = back(id);

    assertThat(answer.status()).isEqualTo(303);
    assertThat(answer.header("Location")).isEqualTo(World.RETURN_PREFIX + "/" + order + "?payment_id=" + id);
    assertThat(answer.raw().body()).isEmpty(); // the page shows nothing
    assertThat(payment(id).text("status")).isEqualTo("CAPTURED"); // looked at before the customer reaches the store
  }

  @Test
  void what_the_address_says_about_the_payment_is_ignored() {
    String id = pay(newOrder(), 15_000, "3333", "AUTO").text("id"); // not paid

    Answer answer = browser.get("/v1/return/" + id + "?status=paid&id=" + id + "&message=APPROVED&amount=1");

    // These words passed through the customer's browser. Anyone can write "status=paid" into an address.
    assertThat(answer.status()).isEqualTo(303);
    assertThat(answer.header("Location")).doesNotContain("status=paid").doesNotContain("APPROVED");
    assertThat(payment(id).text("status")).isEqualTo("PENDING");
    assertThat(entries(id)).isEmpty();
  }

  @Test
  void an_address_of_the_store_that_already_has_a_question_mark_gets_the_id_added() {
    String order = newOrder();
    Map<String, Object> body = card(order, 15_000, "3333", "AUTO");
    body.put("return_url", World.RETURN_PREFIX + "?order=" + order + "#thanks");
    String id = api.post("/v1/payments", body).text("id");

    assertThat(back(id).header("Location")).isEqualTo(World.RETURN_PREFIX + "?order=" + order + "&payment_id=" + id + "#thanks");
  }

  @Test
  void an_unknown_payment_and_a_payment_without_a_return_address_are_not_found() {
    String token = world.simulator.createToken("4111110000001111", "visa", true);
    String wallet = api.post("/v1/payments", Api.body(
        "amount", 5_000, "currency", "SAR", "reference", newOrder(), "method", "APPLE_PAY", "source", Map.of("token", token))).text("id");

    assertThat(back("00000000-0000-0000-0000-000000000000").status()).isEqualTo(404);
    assertThat(back("not-a-uuid").status()).isEqualTo(404);
    assertThat(back(wallet).status()).isEqualTo(404);
    assertThat(back("not-a-uuid").raw().body()).doesNotContain("Exception");
  }

  @Test
  void a_payment_looked_at_less_than_two_seconds_ago_is_not_looked_at_again() {
    String order = newOrder();
    String id = pay(order, 15_000, "3333", "AUTO").text("id"); // looked at just now, by the create
    world.simulator.fetchDown(order, 1); // the next fetch of this order, whenever it comes, fails

    back(id);
    back(id);
    advance(Duration.ofMillis(1_999));
    back(id);
    assertThat(column(id, "sync_failures")).isEqualTo("0"); // no fetch was sent: a stranger cannot flood the gateway from here

    advance(Duration.ofMillis(1));
    Answer answer = back(id);

    assertThat(column(id, "sync_failures")).isEqualTo("1"); // two seconds have passed: this one asked, and the gateway was down
    assertThat(answer.status()).isEqualTo(303); // the customer goes on to the store all the same
  }

  @Test
  void the_return_page_only_looks_and_starts_nothing() {
    // A pay-later payment with automatic capture: the capture is the worker's follow-up, not the return page's.
    String id = api.post("/v1/payments", payLater(newOrder(), 15_000, "AUTO")).text("id");
    world.simulator.customerApproves(gatewayId(id));
    advance(Duration.ofSeconds(3));

    back(id);

    assertThat(payment(id).text("status")).isEqualTo("AUTHORIZED");
    assertThat(column(id, "follow_up")).isEqualTo("AUTO_CAPTURE");
    assertThat(time(id, "next_sync_at")).isEqualTo(now()); // the worker is due at once
    round();
    assertThat(payment(id).text("status")).isEqualTo("CAPTURED");
  }
}
