package dev.mizan.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.app.support.Api.Answer;
import dev.mizan.app.support.IntegrationTest;
import dev.mizan.simulator.Call;
import dev.mizan.simulator.Fault;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Looking at the gateway and writing down what it says (rule R1), without the worker. See docs/lld/sync.md. */
class SyncIT extends IntegrationTest {

  @Test
  void a_customer_who_passes_the_banks_page_is_found_by_a_look_without_any_webhook() {
    String id = pay(newOrder(), 15_000, "3333", "AUTO").text("id");
    world.simulator.customerApproves(id);

    Answer synced = sync(id);

    assertThat(synced.text("status")).isEqualTo("CAPTURED");
    assertThat(synced.json().path("next_action").isNull()).isTrue();
    assertThat(entries(id)).containsExactly("CAPTURE 15000");
    assertThat(events(id)).containsExactly("payment.captured");
    assertThat(column(id, "next_sync_at")).isNull();
  }

  @Test
  void a_customer_who_fails_the_banks_page_fails_the_payment() {
    String id = pay(newOrder(), 15_000, "3333", "AUTO").text("id");
    world.simulator.customerDeclines(id);

    Answer synced = sync(id);

    assertThat(synced.text("status")).isEqualTo("FAILED");
    assertThat(synced.json().path("failure").path("message").asString()).isEqualTo("3-D SECURE FAILED");
    assertThat(events(id)).containsExactly("payment.failed");
  }

  @Test
  void looking_twice_writes_nothing_twice() {
    String id = pay(newOrder(), 15_000, "3333", "AUTO").text("id");
    world.simulator.customerApproves(id);

    sync(id);
    sync(id);
    sync(id);

    assertThat(entries(id)).containsExactly("CAPTURE 15000");
    assertThat(events(id)).containsExactly("payment.captured");
    assertThat(atGateway(id).charges()).isEqualTo(1);
  }

  @Test
  void a_record_with_another_amount_changes_nothing_and_opens_one_discrepancy() {
    String order = newOrder();
    String id = pay(order, 15_000, "3333", "AUTO").text("id");
    world.simulator.customerApproves(id);
    world.simulator.stick(order, Fault.AMOUNT_CHANGED); // the gateway now says "paid", but for 151.00

    Answer synced = sync(id);
    Answer again = sync(id);

    // "Paid" for another amount is not this payment's money. A person must look.
    assertThat(synced.text("status")).isEqualTo("PENDING");
    assertThat(again.status()).isEqualTo(200); // the second look finds the discrepancy open and opens none
    assertThat(entries(id)).isEmpty();
    assertThat(discrepancies(id)).containsExactly("AMOUNT_CONFLICT OPEN 15000 15100 0"); // one, however often Mizan looks
    assertThat(events(id)).containsExactly("discrepancy.opened");
  }

  @Test
  void a_gateway_that_cannot_be_asked_is_counted_and_asked_again_later() {
    String order = newOrder();
    String id = pay(order, 15_000, "3333", "AUTO").text("id");
    world.simulator.customerApproves(id);
    world.simulator.fetchDown(order, 2);

    Answer first = sync(id);
    Instant afterOne = time(id, "next_sync_at");
    sync(id);
    Instant afterTwo = time(id, "next_sync_at");

    assertThat(first.status()).isEqualTo(200);
    assertThat(first.text("status")).isEqualTo("PENDING"); // as Mizan last knew it
    assertThat(column(id, "sync_failures")).isEqualTo("2");
    assertThat(afterOne).isEqualTo(now().plusSeconds(30)); // then 60, 120 ... up to ten minutes
    assertThat(afterTwo).isEqualTo(now().plusSeconds(60));

    // The gateway is back: the count starts again.
    assertThat(sync(id).text("status")).isEqualTo("CAPTURED");
    assertThat(column(id, "sync_failures")).isEqualTo("0");
  }

  @Test
  void a_payment_the_provider_has_given_no_id_for_cannot_be_asked_about() {
    String order = newOrder();
    world.simulator.plan(order, Call.CREATE, Fault.LOST_ANSWER);
    String id = api.post("/v1/payments", payLater(order, 15_000, "MANUAL")).text("id");

    Answer synced = sync(id);

    assertThat(synced.status()).isEqualTo(200);
    assertThat(synced.text("status")).isEqualTo("FAILED");
    assertThat(column(id, "last_synced_at")).isNull();
  }
}
