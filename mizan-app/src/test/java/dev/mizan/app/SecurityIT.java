package dev.mizan.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mizan.app.application.ApiKeyService;
import dev.mizan.app.application.model.ApiKey;
import dev.mizan.app.support.Api;
import dev.mizan.app.support.Api.Answer;
import dev.mizan.app.support.IntegrationTest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Who may call what. See docs/lld/api.md, "Callers", and docs/lld/running.md, "Security details". */
class SecurityIT extends IntegrationTest {

  private final ApiKeyService keys = world.app.getBean(ApiKeyService.class);

  @Test
  void no_key_and_a_wrong_key_are_401() {
    Answer none = api.withKey(null).get("/v1/payments");
    Answer wrong = api.withKey("mzn_this_is_not_a_key").get("/v1/payments");
    Answer empty = api.withKey("").get("/v1/payments");

    assertThat(none.status()).isEqualTo(401);
    assertThat(none.code()).isEqualTo("unauthorized");
    assertThat(none.header("WWW-Authenticate")).isEqualTo("Bearer");
    assertThat(none.header("Content-Type")).startsWith("application/problem+json");
    assertThat(wrong.status()).isEqualTo(401);
    assertThat(empty.status()).isEqualTo(401);
  }

  @Test
  void a_request_without_a_key_makes_nothing() {
    String order = newOrder();

    Answer refused = api.withKey(null).post("/v1/payments", card(order, 15_000, "1111", "AUTO"));

    assertThat(refused.status()).isEqualTo(401);
    assertThat(world.simulator.paymentsOf(order)).isEmpty();
    assertThat(api.get("/v1/payments?reference=" + order).json().path("data")).isEmpty();
  }

  @Test
  void a_key_without_the_scope_is_403() {
    Api finance = api.withKey(world.backofficeKey);

    Answer payments = finance.get("/v1/payments");
    Answer create = finance.post("/v1/payments", card(newOrder(), 15_000, "1111", "AUTO"));
    Answer backoffice = api.get("/v1/ledger/balances"); // the store's key on a finance address

    assertThat(payments.status()).isEqualTo(403);
    assertThat(payments.code()).isEqualTo("forbidden");
    assertThat(payments.text("title")).contains("payments");
    assertThat(create.status()).isEqualTo(403);
    assertThat(backoffice.status()).isEqualTo(403);
    assertThat(backoffice.text("title")).contains("backoffice");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "/v1/payments/../ledger/balances", "/v1/payments/%2e%2e/ledger/balances", "/v1/payments/..;/ledger/balances",
      "/v1/payments/./../ledger/balances", "/v1/payments/%2E%2E/ledger/balances"})
  void a_path_that_only_starts_like_payments_does_not_get_past_the_scope(String path) throws IOException {
    // Sent as it is, byte for byte: an HTTP client would clean the path up before sending it.
    URI base = URI.create(world.baseUrl);
    try (Socket socket = new Socket(base.getHost(), base.getPort())) {
      socket.setSoTimeout(5_000);
      String request = "GET " + path + " HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer " + world.paymentsKey
          + "\r\nConnection: close\r\n\r\n";
      socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
      String statusLine = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();

      assertThat(statusLine).as(path).isNotNull().doesNotContain(" 200 ");
    }
  }

  @Test
  void a_key_is_shown_once_and_only_its_hash_is_stored() {
    String name = "key-" + UUID.randomUUID();

    String key = keys.create(name, Set.of(ApiKey.PAYMENTS));

    assertThat(key).startsWith("mzn_").hasSizeGreaterThan(40);
    String stored = jdbc.sql("SELECT key_hash || ' ' || scopes FROM api_keys WHERE name = ?").param(name).query(String.class).single();
    assertThat(stored).doesNotContain(key).matches("[0-9a-f]{64} payments");
    assertThat(api.withKey(key).get("/v1/payments").status()).isEqualTo(200);
    assertThat(keys.create(name + "-2", Set.of(ApiKey.PAYMENTS))).isNotEqualTo(key); // every key is new
  }

  @Test
  void a_revoked_key_is_refused() {
    String name = "key-" + UUID.randomUUID();
    String key = keys.create(name, Set.of(ApiKey.PAYMENTS, ApiKey.BACKOFFICE));
    assertThat(api.withKey(key).get("/v1/payments").status()).isEqualTo(200);

    assertThat(keys.revoke(name)).isTrue();

    assertThat(api.withKey(key).get("/v1/payments").status()).isEqualTo(401);
    assertThat(keys.revoke(name)).isFalse(); // nothing is left to revoke
  }

  @Test
  void a_key_needs_a_name_and_a_known_scope() {
    assertThatThrownBy(() -> keys.create("", Set.of(ApiKey.PAYMENTS))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> keys.create("x", Set.of())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> keys.create("x", Set.of("admin"))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void every_answer_has_a_request_id_and_must_not_be_stored_by_anyone() {
    Answer answer = api.get("/v1/payments");
    Answer withId = api.withKey(null).get("/v1/payments");

    assertThat(answer.header("X-Request-Id")).isNotBlank();
    assertThat(answer.header("Cache-Control")).isEqualTo("no-store");
    assertThat(answer.header("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(answer.header("Strict-Transport-Security")).contains("max-age");
    assertThat(withId.header("X-Request-Id")).isNotBlank(); // also on a refusal
  }
}
