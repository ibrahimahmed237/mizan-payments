package dev.mizan.app;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.mizan.app.application.CleanUp;
import dev.mizan.app.application.RunDueEvents;
import dev.mizan.app.support.Api;
import dev.mizan.app.support.Api.Answer;
import dev.mizan.app.support.Books;
import dev.mizan.app.support.IntegrationTest;
import dev.mizan.app.support.StoreReceiver;
import dev.mizan.core.payment.Gateway;
import dev.mizan.simulator.Call;
import dev.mizan.simulator.Fault;
import dev.mizan.simulator.FileFault;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/** Running Mizan: limits, logs, metrics, health, the clean-up. See docs/lld/running.md. */
class OperationsIT extends IntegrationTest {

  // ---------------------------------------------------------------- limits

  @Test
  void a_caller_over_its_limit_is_told_when_to_come_back() {
    try (ConfigurableApplicationContext limited = world.startWith(
        "mizan.limits.api-per-second=0.5", "mizan.limits.api-burst=3", "mizan.limits.returns-per-second=0.5", "mizan.limits.returns-burst=1")) {
      String url = "http://localhost:" + ((ServletWebServerApplicationContext) limited).getWebServer().getPort();
      Api store = new Api(url, world.paymentsKey);
      Api other = new Api(url, world.backofficeKey);

      List<Integer> statuses = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        statuses.add(store.get("/v1/payments?limit=1").status());
      }
      Answer refused = store.get("/v1/payments?limit=1");

      assertThat(statuses).containsExactly(200, 200, 200, 429);
      assertThat(refused.code()).isEqualTo("too_many_requests");
      assertThat(Long.parseLong(refused.header("Retry-After"))).isBetween(1L, 2L);
      assertThat(other.get("/v1/ledger/balances").status()).isEqualTo(200); // each key has its own limit

      String id = pay(newOrder(), 15_000, "3333", "AUTO").text("id");
      Api browser = new Api(url, null);
      assertThat(browser.get("/v1/return/" + id).status()).isEqualTo(303);
      assertThat(browser.get("/v1/return/" + id).status()).isEqualTo(429); // the return page has its own, smaller limit
      // Behind a load balancer, the limit is per customer, not for all of them together: the balancer says who it is.
      assertThat(returnsFor("203.0.113.7", url, id)).isEqualTo(303);
      assertThat(returnsFor("203.0.113.7", url, id)).isEqualTo(429);
      assertThat(returnsFor("203.0.113.8", url, id)).isEqualTo(303);
    }
  }

  /** The status of the return page for a customer at this address, as a load balancer in front of Mizan would send it. */
  private static int returnsFor(String customerAddress, String url, String paymentId) {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/v1/return/" + paymentId)).header("X-Forwarded-For", customerAddress).build();
    try {
      return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void a_body_over_64_kb_is_refused_before_it_is_read() {
    String big = "{\"description\":\"" + "x".repeat(70_000) + "\"}";

    Answer refused = api.post("/v1/payments", big);

    assertThat(refused.status()).isEqualTo(413);
    assertThat(refused.code()).isEqualTo("body_too_large");
  }

  @Test
  void a_body_sent_in_pieces_is_held_to_the_same_limit() {
    // A body sent in pieces (chunked) does not say its size, so the size cannot be checked before it is read.
    HttpClient http = HttpClient.newHttpClient();
    Function<String, Integer> sendInPieces = body -> {
      HttpRequest request = HttpRequest.newBuilder(URI.create(world.baseUrl + "/v1/payments"))
          .header("Authorization", "Bearer " + world.paymentsKey)
          .header("Idempotency-Key", UUID.randomUUID().toString())
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))))
          .build();
      try {
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
      } catch (IOException | InterruptedException e) {
        throw new IllegalStateException(e);
      }
    };

    assertThat(sendInPieces.apply("{\"description\":\"" + "x".repeat(70_000) + "\"}")).isEqualTo(413);
    assertThat(sendInPieces.apply("{}")).as("a small one is read as usual, and found incomplete").isEqualTo(422);
  }

  // ---------------------------------------------------------------- logs

  @Test
  void a_token_an_api_key_and_the_secrets_never_appear_in_the_logs() {
    ListAppender<ILoggingEvent> log = new ListAppender<>();
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    log.start();
    root.addAppender(log);
    try {
      String order = newOrder();
      Map<String, Object> body = card(order, 15_000, "1111", "AUTO");
      @SuppressWarnings("unchecked")
      String token = ((Map<String, String>) body.get("source")).get("token");
      world.simulator.plan(order, Call.REFUND, Fault.ERROR_500); // a failure, so error paths log too
      String id = api.post("/v1/payments", body).text("id");
      api.post("/v1/payments/" + id + "/refunds", Map.of("amount", 1_000));
      api.withKey("mzn_wrong_key_that_should_not_be_logged").get("/v1/payments");
      api.send("POST", "/v1/payments", card(order, 1, "1111", "AUTO"), "k-" + UUID.randomUUID());
      world.app.getBean(RunDueEvents.class).handle(1_000);

      List<String> secrets = List.of(
          token, world.paymentsKey, world.backofficeKey, "mzn_wrong_key_that_should_not_be_logged",
          world.simulator.config().cardKey(), world.simulator.config().cardWebhookSecret(),
          world.simulator.config().payLaterWebhookSecret(), StoreReceiver.SECRET);
      assertThat(log.list).isNotEmpty();
      for (ILoggingEvent event : log.list) {
        String line = event.getFormattedMessage() + (event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getMessage());
        for (String secret : secrets) {
          assertThat(line).as("a log line").doesNotContain(secret);
        }
      }
    } finally {
      root.detachAppender(log);
    }
  }

  // ---------------------------------------------------------------- metrics and health

  @Test
  void gateway_calls_webhooks_and_the_state_are_measured() {
    MeterRegistry meters = world.app.getBean(MeterRegistry.class);
    double done = count(meters, "mizan.gateway.calls", "gateway", "card", "call", "create", "result", "done");
    double unknown = count(meters, "mizan.gateway.calls", "gateway", "card", "call", "create", "result", "unknown");
    double refusedWebhooks = count(meters, "mizan.webhooks", "gateway", "card", "result", "unauthorized");
    String order = newOrder();

    captured(newOrder(), 15_000);
    world.simulator.plan(order, Call.CREATE, Fault.ERROR_500);
    pay(order, 15_000, "1111", "AUTO");
    api.withKey(null).send("POST", "/v1/webhooks/card", "{\"secret_token\":\"wrong\"}", null);

    assertThat(count(meters, "mizan.gateway.calls", "gateway", "card", "call", "create", "result", "done")).isEqualTo(done + 1);
    assertThat(count(meters, "mizan.gateway.calls", "gateway", "card", "call", "create", "result", "unknown")).isEqualTo(unknown + 1);
    assertThat(count(meters, "mizan.webhooks", "gateway", "card", "result", "unauthorized")).isEqualTo(refusedWebhooks + 1);
    assertThat(meters.get("mizan.operations.in_flight").gauge().value()).isGreaterThanOrEqualTo(1);
    assertThat(meters.get("mizan.payments").tag("state", "captured").gauge().value()).isGreaterThanOrEqualTo(1);
    assertThat(meters.get("mizan.ledger.suspense").gauge().value()).isEqualTo((double) -Books.balance(jdbc, "suspense"));
  }

  @Test
  void the_gauges_about_payments_say_what_the_table_says() throws InterruptedException {
    MeterRegistry meters = world.app.getBean(MeterRegistry.class);
    captured(newOrder(), 15_000);
    String waiting = pay(newOrder(), 15_000, "3333", "AUTO").text("id"); // waits for the bank's code
    advance(Duration.ofSeconds(60));
    pay(newOrder(), 15_000, "3333", "AUTO"); // a younger one
    advance(Duration.ofSeconds(30));
    Thread.sleep(1_100); // the gauges keep one read of the table for a second

    for (String state : List.of("PENDING", "AUTHORIZED", "CAPTURED", "FAILED", "CANCELED", "EXPIRED")) {
      assertThat(meters.get("mizan.payments").tag("state", state.toLowerCase(java.util.Locale.ROOT)).gauge().value()).as(state)
          .isEqualTo((double) jdbc.sql("SELECT count(*) FROM payments WHERE status = ?").param(state).query(Long.class).single());
    }
    assertThat(meters.get("mizan.late_captures").gauge().value())
        .isEqualTo((double) jdbc.sql("SELECT count(*) FROM payments WHERE late").query(Long.class).single());
    // The oldest pending payment, not the newest: at least the one made here 90 seconds ago, or an earlier test's.
    OffsetDateTime oldest = jdbc.sql("SELECT min(created_at) FROM payments WHERE status = 'PENDING'").query(OffsetDateTime.class).single();
    assertThat(meters.get("mizan.payments.pending.oldest.seconds").gauge().value())
        .isEqualTo((double) Duration.between(oldest.toInstant(), now()).toSeconds())
        .isGreaterThanOrEqualTo(90.0);
    assertThat(payment(waiting).text("status")).isEqualTo("PENDING");
  }

  @Test
  void money_received_and_not_explained_shows_as_a_positive_suspense() {
    MeterRegistry meters = world.app.getBean(MeterRegistry.class);
    freshPayouts();
    captured(newOrder(), 15_000);
    double before = meters.get("mizan.ledger.suspense").gauge().value();
    world.simulator.planFile(FileFault.EXTRA_LINE, null); // 50.00 for a payment that does not exist

    upload(payout(Gateway.CARD_GATEWAY));
    match();

    assertThat(meters.get("mizan.ledger.suspense").gauge().value() - before).isEqualTo((double) 5_000 - cardFee(5_000));
  }

  @Test
  void health_and_metrics_are_served_without_an_api_key_and_nothing_else_of_the_inside_is() {
    Api outside = api.withKey(null);

    Answer liveness = outside.get("/actuator/health/liveness");
    Answer readiness = outside.get("/actuator/health/readiness");
    Answer prometheus = outside.get("/actuator/prometheus");

    assertThat(liveness.status()).isEqualTo(200);
    assertThat(liveness.text("status")).isEqualTo("UP");
    assertThat(readiness.status()).isEqualTo(200);
    assertThat(prometheus.raw().body()).contains("mizan_gateway_calls_total").contains("mizan_outbox_waiting");
    assertThat(outside.get("/actuator/env").status()).isEqualTo(404);
    assertThat(outside.get("/actuator/beans").status()).isEqualTo(404);
  }

  private static double count(MeterRegistry meters, String name, String... tags) {
    var counter = meters.find(name).tags(tags).counter();
    return counter == null ? 0 : counter.count();
  }

  // ---------------------------------------------------------------- the clean-up

  @Test
  void the_clean_up_deletes_old_keys_webhooks_and_delivered_events_and_keeps_the_rest() {
    String id = captured(newOrder(), 15_000);
    String event = jdbc.sql("SELECT id::text FROM outbox_events WHERE payment_id = ?").param(UUID.fromString(id)).query(String.class).single();
    world.app.getBean(RunDueEvents.class).handle(1_000); // delivered
    String webhook = "evt_" + UUID.randomUUID();
    api.withKey(null).send("POST", "/v1/webhooks/card", "{\"id\":\"" + webhook + "\",\"type\":\"payment_paid\",\"secret_token\":\""
        + world.simulator.config().cardWebhookSecret() + "\",\"data\":{\"id\":\"" + id + "\"}}", null);

    advance(Duration.ofDays(91));
    CleanUp.Result result = world.app.getBean(CleanUp.class).handle();

    assertThat(result.idempotencyKeys()).isGreaterThanOrEqualTo(1);
    assertThat(jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE expires_at <= ?").param(OffsetDateTime.ofInstant(now(), ZoneOffset.UTC))
        .query(Long.class).single()).isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM gateway_events WHERE event_id = ?").param(webhook).query(Long.class).single()).isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_events WHERE id = ?").param(UUID.fromString(event)).query(Long.class).single()).isZero();
    // Financial records stay.
    assertThat(payment(id).status()).isEqualTo(200);
    assertThat(entries(id)).containsExactly("CAPTURE 15000");
  }

  @Test
  void a_waiting_or_failed_event_is_never_cleaned_up() {
    String id = captured(newOrder(), 15_000);
    String event = jdbc.sql("SELECT id::text FROM outbox_events WHERE payment_id = ?").param(UUID.fromString(id)).query(String.class).single();
    jdbc.sql("UPDATE outbox_events SET status = 'FAILED', next_attempt_at = NULL WHERE id = ?").param(UUID.fromString(event)).update();

    advance(Duration.ofDays(91));
    world.app.getBean(CleanUp.class).handle();

    assertThat(jdbc.sql("SELECT status FROM outbox_events WHERE id = ?").param(UUID.fromString(event)).query(String.class).single()).isEqualTo("FAILED");
  }

  @Test
  void a_payout_file_is_a_financial_record_and_is_kept() {
    freshPayouts();
    captured(newOrder(), 15_000);
    String file = upload(payout(Gateway.CARD_GATEWAY)).text("id");

    advance(Duration.ofDays(400));
    world.app.getBean(CleanUp.class).handle();

    assertThat(finance.get("/v1/settlements/" + file).status()).isEqualTo(200);
    match();
  }
}
