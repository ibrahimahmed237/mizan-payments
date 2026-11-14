package dev.mizan.app.support;

import dev.mizan.app.MizanApplication;
import dev.mizan.app.application.ApiKeyService;
import dev.mizan.app.application.model.ApiKey;
import com.sun.net.httpserver.HttpServer;
import dev.mizan.core.payment.Gateway;
import dev.mizan.simulator.MutableClock;
import dev.mizan.simulator.Simulator;
import dev.mizan.simulator.SimulatorServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * Everything an integration test needs, started once for the whole test run: a real PostgreSQL in a container, the
 * simulator of both gateways, and Mizan itself on a free port. Mizan and the simulator share one clock that only
 * tests move.
 *
 * <p>Tests share this world and never clean it: every test makes its own orders with new references.
 */
public final class World {

  public static final Instant START = Instant.parse("2026-11-14T09:00:00Z");
  /** How long Mizan waits for a gateway's answer in tests. The simulator's faults hang for longer. */
  public static final Duration GATEWAY_TIMEOUT = Duration.ofMillis(400);
  public static final String RETURN_PREFIX = "https://store.example/orders";

  private static World instance;

  public final MutableClock clock = new MutableClock(START);
  public final Simulator simulator = new Simulator(clock, Simulator.Config.defaults().withHang(Duration.ofMillis(900)));
  public final SimulatorServer simulatorServer = SimulatorServer.start(simulator, 0);
  /** The store's backend, where Mizan sends its events. */
  public final StoreReceiver store = new StoreReceiver();
  public final ConfigurableApplicationContext app;
  public final String baseUrl;
  public final JdbcClient jdbc;
  /** A key with the scope "payments", and one with the scope "backoffice". */
  public final String paymentsKey;
  public final String backofficeKey;

  public static synchronized World get() {
    if (instance == null) {
      instance = new World();
    }
    return instance;
  }

  /**
   * Mizan a second time, on the same database and the same simulator, with some settings changed. For the few tests
   * about a setting that the first one does not have.
   */
  public record Second(ConfigurableApplicationContext app, String baseUrl, String paymentsKey) {}

  private final Map<String, Second> seconds = new HashMap<>();

  public synchronized Second second(String setting) {
    return seconds.computeIfAbsent(setting, changed -> {
      Map<String, String> all = new HashMap<>(settings);
      all.put(changed.substring(0, changed.indexOf('=')), changed.substring(changed.indexOf('=') + 1));
      ConfigurableApplicationContext other = start(all);
      return new Second(other, url(other), other.getBean(ApiKeyService.class).create("second-" + changed, Set.of(ApiKey.PAYMENTS)));
    });
  }

  /**
   * Mizan once more, with some settings changed, for the caller to close when it is done. Unlike {@link #second},
   * nothing is kept: a test that switches the workers on must switch them off again before the next test runs.
   */
  public ConfigurableApplicationContext startWith(String... changed) {
    Map<String, String> all = new HashMap<>(settings);
    for (String setting : changed) {
      all.put(setting.substring(0, setting.indexOf('=')), setting.substring(setting.indexOf('=') + 1));
    }
    return start(all);
  }

  private final Map<String, String> settings;

  /** The orders whose webhooks reach Mizan. For every other order the gateway's webhooks are dropped on the way. */
  private final Set<String> ordersWithWebhooks = ConcurrentHashMap.newKeySet();
  /** Every webhook the simulator sent, for any order, as {@code order: event}. */
  public final List<String> webhooksSent = new CopyOnWriteArrayList<>();

  /**
   * From now on the simulator's webhooks about this order are passed on to Mizan. Most tests want silence: a
   * webhook that arrives a moment later would change when the worker looks next, behind the test's back.
   */
  public void webhooksFor(String order) {
    ordersWithWebhooks.add(order);
  }

  @SuppressWarnings("resource") // the container lives as long as the test run; Testcontainers removes it at the end
  private World() {
    var postgres = new PostgreSQLContainer(System.getProperty("mizan.test.postgres-image", "postgres:17-alpine"));
    postgres.start();

    settings = Map.ofEntries(
        Map.entry("server.port", "0"),
        Map.entry("spring.datasource.url", postgres.getJdbcUrl()),
        Map.entry("spring.datasource.username", postgres.getUsername()),
        Map.entry("spring.datasource.password", postgres.getPassword()),
        Map.entry("mizan.public-url", "https://mizan.example"),
        Map.entry("mizan.return-url-prefixes", RETURN_PREFIX),
        Map.entry("mizan.card-gateway.url", simulatorServer.baseUrl() + "/card/v1"),
        Map.entry("mizan.card-gateway.key", simulator.config().cardKey()),
        Map.entry("mizan.card-gateway.webhook-secret", simulator.config().cardWebhookSecret()),
        Map.entry("mizan.pay-later.url", simulatorServer.baseUrl() + "/paylater/v2"),
        Map.entry("mizan.pay-later.key", simulator.config().payLaterKey()),
        Map.entry("mizan.pay-later.webhook-secret", simulator.config().payLaterWebhookSecret()),
        Map.entry("mizan.http.timeout", GATEWAY_TIMEOUT.toMillis() + "ms"),
        Map.entry("mizan.store.event-url", store.url()),
        Map.entry("mizan.store.event-secret", StoreReceiver.SECRET),
        Map.entry("mizan.workers.enabled", "false"));

    app = start(settings);
    baseUrl = url(app);
    relayWebhooks();
    jdbc = app.getBean(JdbcClient.class);
    ApiKeyService keys = app.getBean(ApiKeyService.class);
    paymentsKey = keys.create("test-store", Set.of(ApiKey.PAYMENTS));
    backofficeKey = keys.create("test-finance", Set.of(ApiKey.BACKOFFICE));
  }

  /**
   * Stands between the simulator and Mizan. The simulator sends every webhook here; this passes it on to Mizan,
   * as it came, only for the orders that asked for webhooks, and gives Mizan's answer back to the simulator.
   */
  private void relayWebhooks() {
    try {
      HttpServer relay = HttpServer.create(new InetSocketAddress(0), 0);
      relay.createContext("/", exchange -> {
        try (exchange) {
          byte[] body = exchange.getRequestBody().readAllBytes();
          JsonNode json = Api.JSON.readTree(body);
          boolean card = exchange.getRequestURI().getPath().endsWith("/card");
          String order = card ? json.path("data").path("metadata").path("reference").asString("") : json.path("meta").path("order_id").asString("");
          webhooksSent.add(order + ": " + (card ? json.path("type").asString("") : json.path("status").asString("")));
          int status = 200;
          if (ordersWithWebhooks.contains(order)) {
            var forward = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/webhooks/" + (card ? "card" : "pay-later")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            String signature = exchange.getRequestHeaders().getFirst("X-Signature");
            if (signature != null) {
              forward.header("X-Signature", signature);
            }
            status = HttpClient.newHttpClient().send(forward.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
          }
          exchange.sendResponseHeaders(status, -1);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      relay.start();
      String url = "http://localhost:" + relay.getAddress().getPort();
      simulator.webhookTarget(Gateway.CARD_GATEWAY, URI.create(url + "/card"));
      simulator.webhookTarget(Gateway.PAY_LATER, URI.create(url + "/pay-later"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private ConfigurableApplicationContext start(Map<String, String> with) {
    return new SpringApplicationBuilder(MizanApplication.class)
        // Mizan runs on the test's clock, the same one the simulator has.
        .initializers(context -> ((GenericApplicationContext) context).registerBean("clock", InstantSource.class, () -> clock))
        // As command-line arguments, so they win over application.yml.
        .run(with.entrySet().stream().map(setting -> "--" + setting.getKey() + "=" + setting.getValue()).toArray(String[]::new));
  }

  private static String url(ConfigurableApplicationContext context) {
    return "http://localhost:" + ((ServletWebServerApplicationContext) context).getWebServer().getPort();
  }
}
