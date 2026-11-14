package dev.mizan.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.app.adapter.out.store.HttpStoreEndpoint;
import dev.mizan.app.application.RunDueEvents;
import dev.mizan.app.support.Api.Answer;
import dev.mizan.app.support.IntegrationTest;
import dev.mizan.app.support.World;
import dev.mizan.app.support.StoreReceiver;
import dev.mizan.app.support.StoreReceiver.Received;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;

/** Events sent to the store: the signature, the retries, redelivery. See docs/lld/api.md, "Events sent to the store". */
class EventSenderIT extends IntegrationTest {

  @AfterEach
  void theStoreAnswersAgain() {
    world.store.status.set(200);
    world.store.delayMillis.set(0);
  }

  private int send() {
    int sent = 0;
    for (int taken = world.app.getBean(RunDueEvents.class).handle(1_000); taken > 0; taken = world.app.getBean(RunDueEvents.class).handle(1_000)) {
      sent += taken;
    }
    return sent;
  }

  /** The id of a payment's only event. */
  private String onlyEvent(String paymentId) {
    return jdbc.sql("SELECT id::text FROM outbox_events WHERE payment_id = ?").param(UUID.fromString(paymentId)).query(String.class).single();
  }

  private static String hmac(String secret, String text) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private String status(String eventId) {
    return jdbc.sql("SELECT status || ' ' || attempts FROM outbox_events WHERE id = ?").param(UUID.fromString(eventId)).query(String.class).single();
  }

  @Test
  void an_event_is_sent_once_signed_with_the_secret_shared_with_the_store() {
    String id = captured(newOrder(), 15_000);
    String event = onlyEvent(id);

    send();
    send();

    List<Received> requests = world.store.of(event);
    assertThat(requests).hasSize(1);
    Received request = requests.get(0);
    assertThat(request.timestamp()).isEqualTo(Long.toString(now().getEpochSecond()));
    // Computed here as the store would, not with Mizan's own code: HMAC-SHA256 of "timestamp.body".
    assertThat(request.signature()).isEqualTo("v1=" + hmac(StoreReceiver.SECRET, now().getEpochSecond() + "." + request.body()));
    assertThat(request.signature()).isEqualTo("v1=" + HttpStoreEndpoint.sign(StoreReceiver.SECRET, now().getEpochSecond(), request.body()));
    assertThat(request.signature()).isNotEqualTo("v1=" + hmac("another-secret", now().getEpochSecond() + "." + request.body()));
    JsonNode body = dev.mizan.app.support.Api.JSON.readTree(request.body());
    assertThat(body.path("id").asString()).isEqualTo(event);
    assertThat(body.path("type").asString()).isEqualTo("payment.captured");
    assertThat(body.path("data").path("payment").path("id").asString()).isEqualTo(id);
    String stored = jdbc.sql("SELECT payload FROM outbox_events WHERE id = ?").param(UUID.fromString(event)).query(String.class).single();
    assertThat(request.body()).isEqualTo(stored); // sent exactly as it was written, in the transaction of the change
    assertThat(status(event)).isEqualTo("DELIVERED 1");
  }

  @Test
  void a_store_that_does_not_answer_well_gets_the_event_again_after_1_5_and_30_minutes_2_6_and_24_hours() {
    String event = onlyEvent(captured(newOrder(), 15_000));
    world.store.status.set(503);

    send();
    for (Duration wait : RunDueEvents.RETRIES) {
      assertThat(world.jdbc.sql("SELECT next_attempt_at FROM outbox_events WHERE id = ?").param(UUID.fromString(event))
          .query(java.time.OffsetDateTime.class).single().toInstant()).isEqualTo(now().plus(wait));
      advance(wait.minusSeconds(1));
      send();
      int before = world.store.of(event).size();
      advance(Duration.ofSeconds(1));
      send();
      assertThat(world.store.of(event)).hasSize(before + 1);
    }

    assertThat(world.store.of(event)).hasSize(7); // the first send and six more
    assertThat(status(event)).isEqualTo("FAILED 7"); // it waits for a person now
    assertThat(jdbc.sql("SELECT last_error FROM outbox_events WHERE id = ?").param(UUID.fromString(event)).query(String.class).single())
        .isEqualTo("http_503");
    advance(Duration.ofDays(3));
    send();
    assertThat(world.store.of(event)).hasSize(7); // and is not sent by itself any more
  }

  @Test
  void a_store_that_comes_back_gets_the_event_and_it_is_delivered() {
    String event = onlyEvent(captured(newOrder(), 15_000));
    world.store.status.set(500);
    send();
    world.store.status.set(204);

    advance(Duration.ofMinutes(1));
    send();

    assertThat(status(event)).isEqualTo("DELIVERED 2");
    assertThat(world.store.of(event)).hasSize(2);
  }

  @Test
  void a_store_slower_than_the_time_limit_has_not_answered_and_gets_the_event_again() {
    String event = onlyEvent(captured(newOrder(), 15_000));
    world.store.delayMillis.set((int) World.GATEWAY_TIMEOUT.toMillis() * 3);

    send();

    assertThat(status(event)).isEqualTo("WAITING 1");
    assertThat(jdbc.sql("SELECT last_error FROM outbox_events WHERE id = ?").param(UUID.fromString(event)).query(String.class).single())
        .startsWith("no_answer");
    world.store.delayMillis.set(0);
    advance(Duration.ofMinutes(1));
    send();
    assertThat(status(event)).isEqualTo("DELIVERED 2"); // the store must ignore the first copy if it got it after all
  }

  @Test
  void finance_sees_the_events_and_sends_one_again() {
    String id = captured(newOrder(), 15_000);
    String event = onlyEvent(id);
    send();

    JsonNode listed = finance.get("/v1/events?payment_id=" + id).json().path("data");
    Answer again = finance.post("/v1/events/" + event + "/redeliver", null);
    Answer twice = finance.post("/v1/events/" + event + "/redeliver", null);
    send();

    assertThat(listed).hasSize(1);
    assertThat(listed.get(0).path("status").asString()).isEqualTo("DELIVERED");
    assertThat(listed.get(0).has("payload")).isFalse(); // finance sees what was sent where, not the body
    assertThat(again.status()).isEqualTo(200);
    assertThat(again.text("status")).isEqualTo("WAITING");
    assertThat(twice.status()).isEqualTo(409);
    assertThat(twice.code()).isEqualTo("already_waiting");
    assertThat(world.store.of(event)).hasSize(2); // the same event, with the same id: the store can drop a repeat
    assertThat(status(event)).isEqualTo("DELIVERED 1"); // counted from zero again: a redelivered event gets all its tries
    assertThat(finance.get("/v1/events?status=DELIVERED&payment_id=" + id).json().path("data")).hasSize(1);
    assertThat(finance.get("/v1/events?status=LOST").code()).isEqualTo("invalid_status");
    assertThat(finance.post("/v1/events/" + UUID.randomUUID() + "/redeliver", null).status()).isEqualTo(404);
    assertThat(api.get("/v1/events").status()).isEqualTo(403); // for finance only
  }

  @Test
  void without_a_store_address_events_are_kept_and_not_sent() {
    String id = captured(newOrder(), 15_000);
    String event = onlyEvent(id);

    try (ConfigurableApplicationContext noStore = world.startWith("mizan.store.event-url=", "mizan.store.event-secret=")) {
      noStore.getBean(RunDueEvents.class).handle(1_000);
    }

    assertThat(status(event)).isEqualTo("SKIPPED 0");
    assertThat(world.store.of(event)).isEmpty();
  }
}
