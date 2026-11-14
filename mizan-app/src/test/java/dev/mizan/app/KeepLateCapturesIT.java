package dev.mizan.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.app.support.Api;
import dev.mizan.app.support.Api.Answer;
import dev.mizan.app.support.IntegrationTest;
import dev.mizan.app.support.World;
import org.junit.jupiter.api.Test;

/**
 * The late-capture policy "keep": money taken on a payment that had ended is kept, unless the order already has
 * another payment's money. Mizan runs a second time here, with that one setting changed. See docs/lld/sync.md.
 */
class KeepLateCapturesIT extends IntegrationTest {

  private final World.Second keeping = world.second("mizan.payment.late-capture=KEEP");
  private final Api store = new Api(keeping.baseUrl(), keeping.paymentsKey());

  private Answer look(String id) {
    return store.send("POST", "/v1/payments/" + id + "/sync", null, null);
  }

  @Test
  void a_late_capture_is_kept_when_the_order_has_no_other_money() {
    String order = newOrder();
    String waiting = store.post("/v1/payments", card(order, 15_000, "3333", "AUTO")).text("id");
    String id = store.post("/v1/payments", card(order, 15_000, "3333", "AUTO")).text("id");
    store.post("/v1/payments/" + id + "/cancel", null);

    world.simulator.customerApproves(id); // the customer pays after the cancel
    Answer late = look(id);

    assertThat(late.text("status")).isEqualTo("CAPTURED");
    assertThat(late.json().path("late").asBoolean()).isTrue();
    assertThat(column(id, "follow_up")).isNull(); // kept: its own money is not "another payment's money"
    assertThat(entries(id)).containsExactly("CAPTURE 15000");
    assertThat(events(id)).containsExactly("payment.canceled", "payment.captured");
    // The order is paid now, so its other waiting payment ends.
    assertThat(store.get("/v1/payments/" + waiting).text("status")).isEqualTo("CANCELED");
  }

  @Test
  void a_late_capture_is_returned_all_the_same_when_another_payment_has_the_orders_money() {
    String order = newOrder();
    String loser = store.post("/v1/payments", card(order, 15_000, "3333", "AUTO")).text("id");
    String winner = store.post("/v1/payments", card(order, 15_000, "1111", "AUTO")).text("id");
    assertThat(store.get("/v1/payments/" + loser).text("status")).isEqualTo("CANCELED");

    world.simulator.customerApproves(loser);
    Answer late = look(loser);

    assertThat(late.json().path("late").asBoolean()).isTrue();
    assertThat(late.number("refunded_amount")).isEqualTo(15_000); // returned: one order, one payment that counts
    assertThat(atGateway(loser).refunded()).isEqualTo(15_000);
    assertThat(store.get("/v1/payments/" + winner).text("status")).isEqualTo("CAPTURED");
    assertThat(store.get("/v1/payments/" + winner).number("refunded_amount")).isZero();
  }
}
