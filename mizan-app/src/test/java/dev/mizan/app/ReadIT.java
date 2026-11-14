package dev.mizan.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.app.support.Api.Answer;
import dev.mizan.app.support.IntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Reading payments: one, a list with pages, and a payment's refunds. See docs/lld/api.md. */
class ReadIT extends IntegrationTest {

  @Test
  void a_payment_is_read_with_everything_that_was_done_to_it() {
    String id = authorized(newOrder(), 15_000);
    api.post("/v1/payments/" + id + "/capture", Map.of("amount", 12_000));
    api.post("/v1/payments/" + id + "/refunds", Map.of("amount", 2_000, "reason", "Damaged"));

    Answer read = payment(id);

    assertThat(read.status()).isEqualTo(200);
    assertThat(read.text("id")).isEqualTo(id);
    assertThat(read.text("status")).isEqualTo("CAPTURED");
    assertThat(read.number("captured_amount")).isEqualTo(12_000);
    assertThat(read.number("refunded_amount")).isEqualTo(2_000);
    JsonNode operations = read.json().path("operations");
    assertThat(operations).hasSize(3);
    assertThat(operations.get(0).path("type").asString()).isEqualTo("CREATE");
    assertThat(operations.get(1).path("type").asString()).isEqualTo("CAPTURE");
    assertThat(operations.get(1).path("amount").asLong()).isEqualTo(12_000);
    assertThat(operations.get(2).path("type").asString()).isEqualTo("REFUND");
    assertThat(operations.get(2).path("reason").asString()).isEqualTo("Damaged");
    assertThat(operations.get(2).path("status").asString()).isEqualTo("SUCCEEDED");
    assertThat(operations.get(2).path("finished_at").asString()).isNotBlank();
  }

  @Test
  void the_version_grows_with_every_change() {
    Answer created = pay(newOrder(), 15_000, "1111", "AUTO");
    String id = created.text("id");

    api.post("/v1/payments/" + id + "/refunds", Map.of("amount", 1_000));
    long afterRefund = payment(id).number("version");
    sync(id); // nothing new at the gateway
    long afterSync = payment(id).number("version");

    assertThat(afterRefund).isGreaterThan(created.number("version"));
    assertThat(afterSync).isEqualTo(afterRefund); // a look that finds nothing new is not a change
    assertThat(payment(id).text("updated_at")).isNotBlank();
  }

  @Test
  void a_payments_refunds_are_listed_oldest_first() {
    String id = captured(newOrder(), 15_000);
    api.post("/v1/payments/" + id + "/refunds", Map.of("amount", 1_000));
    api.post("/v1/payments/" + id + "/refunds", Map.of("amount", 2_000));

    JsonNode refunds = api.get("/v1/payments/" + id + "/refunds").json().path("data");

    assertThat(refunds).hasSize(2);
    assertThat(refunds.get(0).path("amount").asLong()).isEqualTo(1_000);
    assertThat(refunds.get(1).path("amount").asLong()).isEqualTo(2_000);
    assertThat(api.get("/v1/payments/00000000-0000-0000-0000-000000000000/refunds").status()).isEqualTo(404);
  }

  @Test
  void payments_are_listed_by_order_newest_first() {
    String order = newOrder();
    String first = pay(order, 15_000, "0002", "AUTO").text("id");
    advance(Duration.ofSeconds(1));
    String second = pay(order, 15_000, "0069", "AUTO").text("id");
    advance(Duration.ofSeconds(1));
    String third = pay(order, 15_000, "1111", "AUTO").text("id");

    JsonNode all = api.get("/v1/payments?reference=" + order).json();
    JsonNode failed = api.get("/v1/payments?reference=" + order + "&status=FAILED").json();

    assertThat(ids(all.path("data"))).containsExactly(third, second, first);
    assertThat(all.path("next_cursor").isNull()).isTrue();
    assertThat(ids(failed.path("data"))).containsExactly(second, first);
    assertThat(all.path("data").get(0).has("operations")).isFalse(); // a list shows payments, not their operations
    assertThat(api.get("/v1/payments?reference=" + newOrder()).json().path("data")).isEmpty();
  }

  @Test
  void a_long_list_comes_in_pages_and_every_payment_comes_once() {
    String order = newOrder();
    List<String> made = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      made.add(pay(order, 1_000 + i, "0002", "AUTO").text("id")); // all at the same instant: the id breaks the tie
    }

    List<String> seen = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      JsonNode page = api.get("/v1/payments?reference=" + order + "&limit=2" + (cursor == null ? "" : "&cursor=" + cursor)).json();
      seen.addAll(ids(page.path("data")));
      cursor = page.path("next_cursor").asString(null);
      pages++;
    } while (cursor != null);

    assertThat(pages).isEqualTo(3);
    assertThat(seen).containsExactlyInAnyOrderElementsOf(made).doesNotHaveDuplicates();
  }

  @Test
  void a_page_that_holds_exactly_the_rest_has_no_next_page() {
    String order = newOrder();
    pay(order, 1_000, "0002", "AUTO");
    pay(order, 1_000, "0002", "AUTO");

    JsonNode exact = api.get("/v1/payments?reference=" + order + "&limit=2").json();
    JsonNode one = api.get("/v1/payments?reference=" + order + "&limit=1").json();

    assertThat(exact.path("data")).hasSize(2);
    assertThat(exact.path("next_cursor").isNull()).isTrue();
    assertThat(one.path("next_cursor").isNull()).isFalse();
  }

  @Test
  void the_page_size_is_kept_between_1_and_100() {
    String order = newOrder();
    for (int i = 0; i < 101; i++) { // written straight into the table: only their number matters here
      jdbc.sql("""
          INSERT INTO payments (id, reference, amount, currency, method, gateway, capture_mode, status, created_at, updated_at)
          VALUES (gen_random_uuid(), ?, 1000, 'SAR', 'CARD', 'CARD_GATEWAY', 'AUTO', 'FAILED', now(), now())
          """).param(order).update();
    }

    JsonNode most = api.get("/v1/payments?reference=" + order + "&limit=1000").json();

    assertThat(api.get("/v1/payments?reference=" + order + "&limit=0").json().path("data")).hasSize(1);
    assertThat(api.get("/v1/payments?reference=" + order + "&limit=-5").json().path("data")).hasSize(1);
    assertThat(most.path("data")).hasSize(100);
    assertThat(most.path("next_cursor").isNull()).isFalse();
    assertThat(api.get("/v1/payments?reference=" + order).json().path("data")).hasSize(20); // the default
  }

  @Test
  void asking_for_a_sync_returns_the_payment_as_it_is_after_the_look() {
    String id = pay(newOrder(), 15_000, "3333", "AUTO").text("id");
    world.simulator.customerApproves(id);

    Answer synced = sync(id);

    assertThat(synced.status()).isEqualTo(200);
    assertThat(synced.text("status")).isEqualTo("CAPTURED");
    assertThat(column(id, "last_synced_at")).isNotNull();
    assertThat(sync("00000000-0000-0000-0000-000000000000").status()).isEqualTo(404);
  }

  private static List<String> ids(JsonNode payments) {
    List<String> ids = new ArrayList<>();
    payments.forEach(payment -> ids.add(payment.path("id").asString()));
    return ids;
  }
}
