package dev.mizan.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.app.support.Api.Answer;
import dev.mizan.app.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What an event for the store says. It is written in the same transaction as the change it tells, and its body is
 * made once, then sent as written. Sending is a later milestone. See docs/lld/api.md, "Events sent to the store".
 */
class EventPayloadIT extends IntegrationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The bodies of a payment's events, oldest first, each with the id of its row. */
  private List<JsonNode> bodies(String paymentId) {
    return jdbc.sql("SELECT payload FROM outbox_events WHERE payment_id = ? ORDER BY seq")
        .param(UUID.fromString(paymentId)).query(String.class).list().stream().map(JSON::readTree).toList();
  }

  @Test
  void a_payments_event_carries_the_payment_as_it_is_after_the_change() {
    Answer created = pay(newOrder(), 15_000, "1111", "AUTO");
    String id = created.text("id");

    JsonNode event = bodies(id).get(0);

    assertThat(event.path("type").asString()).isEqualTo("payment.captured");
    assertThat(event.path("created_at").asString()).isEqualTo(now().toString());
    JsonNode payment = event.path("data").path("payment");
    assertThat(payment.path("id").asString()).isEqualTo(id);
    assertThat(payment.path("status").asString()).isEqualTo("CAPTURED"); // after the change, not before
    assertThat(payment.path("captured_amount").asLong()).isEqualTo(15_000);
    assertThat(payment.path("version").asLong()).isEqualTo(created.number("version"));
    assertThat(payment).isEqualTo(created.json()); // exactly what the API returns
    assertThat(event.path("data").path("operation").isNull()).isTrue();
  }

  @Test
  void an_events_id_is_its_own_and_the_same_in_the_body_and_the_row() {
    String id = captured(newOrder(), 15_000);
    api.post("/v1/payments/" + id + "/refunds", Map.of("amount", 1_000));

    List<String> rowIds = jdbc.sql("SELECT id::text FROM outbox_events WHERE payment_id = ? ORDER BY seq").param(UUID.fromString(id)).query(String.class).list();
    List<String> bodyIds = bodies(id).stream().map(event -> event.path("id").asString()).toList();

    assertThat(bodyIds).isEqualTo(rowIds).doesNotHaveDuplicates().hasSize(2);
    assertThat(jdbc.sql("SELECT DISTINCT status FROM outbox_events WHERE payment_id = ?").param(UUID.fromString(id)).query(String.class).list())
        .containsExactly("WAITING");
  }

  @Test
  void an_operations_event_carries_the_operation_and_the_payment() {
    String id = captured(newOrder(), 15_000);
    Answer refund = api.post("/v1/payments/" + id + "/refunds", Map.of("amount", 4_000, "reason", "Damaged"));

    JsonNode event = bodies(id).get(1);

    assertThat(event.path("type").asString()).isEqualTo("refund.succeeded");
    JsonNode operation = event.path("data").path("operation");
    assertThat(operation.path("id").asString()).isEqualTo(refund.text("id"));
    assertThat(operation.path("type").asString()).isEqualTo("REFUND");
    assertThat(operation.path("status").asString()).isEqualTo("SUCCEEDED");
    assertThat(operation.path("amount").asLong()).isEqualTo(4_000);
    assertThat(operation.path("reason").asString()).isEqualTo("Damaged");
    assertThat(operation.path("payment_id").asString()).isEqualTo(id);
    assertThat(event.path("data").path("payment").path("refunded_amount").asLong()).isEqualTo(4_000);
    assertThat(event.path("data").path("payment").path("version").asLong()).isEqualTo(payment(id).number("version"));
  }

  @Test
  void a_failed_operation_has_an_event_with_the_reason() {
    String order = newOrder();
    String id = authorized(order, 15_000);
    world.simulator.stick(order, dev.mizan.simulator.Fault.HOLD_RELEASED);
    api.post("/v1/payments/" + id + "/capture", null);

    JsonNode event = bodies(id).get(1);

    assertThat(event.path("type").asString()).isEqualTo("capture.failed");
    assertThat(event.path("data").path("operation").path("status").asString()).isEqualTo("FAILED");
    assertThat(event.path("data").path("operation").path("failure").path("code").asString()).isEqualTo("gateway_refused");
    assertThat(event.path("data").path("payment").path("status").asString()).isEqualTo("AUTHORIZED");
  }

  @Test
  void a_change_that_fails_leaves_no_event_and_no_entry_behind() {
    // An amount conflict changes nothing in the payment. Its only trace is the discrepancy and that one event.
    String order = newOrder();
    String id = pay(order, 15_000, "3333", "AUTO").text("id");
    world.simulator.customerApproves(id);
    world.simulator.stick(order, dev.mizan.simulator.Fault.AMOUNT_CHANGED);

    sync(id);

    assertThat(events(id)).containsExactly("discrepancy.opened");
    assertThat(entries(id)).isEmpty();
    assertThat(bodies(id).get(0).path("data").path("payment").path("status").asString()).isEqualTo("PENDING");
  }
}
