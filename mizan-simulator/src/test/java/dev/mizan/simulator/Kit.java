package dev.mizan.simulator;

import com.sun.net.httpserver.HttpServer;
import dev.mizan.core.payment.Gateway;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.JsonNode;

/** A simulator on a free port with a clock the test moves, a client for its APIs, and a place that receives webhooks. */
final class Kit implements AutoCloseable {

  /** An HTTP answer. */
  record Answer(int status, String text) {
    JsonNode json() {
      return Wire.JSON.readTree(text);
    }
  }

  /**
   * One webhook as it arrived.
   *
   * @param answersBefore how many calls of this kit had been answered when it arrived
   */
  record Received(String body, String signature, int answersBefore) {
    JsonNode json() {
      return Wire.JSON.readTree(body);
    }
  }

  static final Instant START = Instant.parse("2026-11-14T09:00:00Z");
  static final String RETURN_URL = "https://mizan.example/v1/return/1";

  final MutableClock clock = new MutableClock(START);
  final Simulator simulator = new Simulator(clock, Simulator.Config.defaults().withHang(Duration.ofMillis(600)));
  final SimulatorServer server = SimulatorServer.start(simulator, 0);
  final List<Received> webhooks = new CopyOnWriteArrayList<>();
  /** How long the client waits for an answer. Fault tests make it shorter than the simulator's hang time. */
  volatile Duration patience = Duration.ofSeconds(5);
  /** How many of the next webhooks are answered with 500. */
  final AtomicInteger failNextWebhooks = new AtomicInteger();
  /** How many calls of this kit were answered so far. */
  final AtomicInteger answers = new AtomicInteger();
  private final HttpServer receiver;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  Kit() {
    try {
      receiver = HttpServer.create(new InetSocketAddress(0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    receiver.createContext("/", exchange -> {
      try (exchange) {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        webhooks.add(new Received(body, exchange.getRequestHeaders().getFirst("X-Signature"), answers.get()));
        exchange.sendResponseHeaders(failNextWebhooks.getAndUpdate(n -> Math.max(0, n - 1)) > 0 ? 500 : 200, -1);
      }
    });
    receiver.start();
    URI target = URI.create("http://localhost:" + receiver.getAddress().getPort() + "/hooks");
    simulator.webhookTarget(Gateway.CARD_GATEWAY, target);
    simulator.webhookTarget(Gateway.PAY_LATER, target);
  }

  @Override
  public void close() {
    server.close();
    receiver.stop(0);
  }

  // ---------------------------------------------------------------- calls

  Answer card(String method, String path, String json) throws IOException {
    String key = Base64.getEncoder().encodeToString((simulator.config().cardKey() + ":").getBytes(StandardCharsets.UTF_8));
    return send(method, "/card/v1" + path, json, "Basic " + key);
  }

  Answer payLater(String method, String path, String json) throws IOException {
    return send(method, "/paylater/v2" + path, json, "Bearer " + simulator.config().payLaterKey());
  }

  Answer send(String method, String path, String json, String authorization) throws IOException {
    var request = HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
        .timeout(patience)
        .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
    if (authorization != null) {
      request.header("Authorization", authorization);
    }
    try {
      HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      answers.incrementAndGet();
      return new Answer(response.statusCode(), response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** A token for a test card ending in the given four digits. */
  String token(String last4) throws IOException {
    return card("POST", "/tokens", "{\"number\":\"588845000000" + last4 + "\",\"scheme\":\"mada\"}").json().path("id").asString();
  }

  String walletToken(String last4) throws IOException {
    return card("POST", "/tokens", "{\"number\":\"411111000000" + last4 + "\",\"scheme\":\"visa\",\"type\":\"applepay\"}").json().path("id").asString();
  }

  static String newId() {
    return UUID.randomUUID().toString();
  }

  static String createBody(String id, long amount, String token, boolean manual, String reference) {
    return "{\"given_id\":\"" + id + "\",\"amount\":" + amount + ",\"currency\":\"SAR\",\"manual\":" + manual
        + ",\"callback_url\":\"" + RETURN_URL + "\",\"source\":{\"type\":\"token\",\"token\":\"" + token + "\"},"
        + "\"metadata\":{\"reference\":\"" + reference + "\",\"mizan_payment_id\":\"" + id + "\"}}";
  }

  /** Creates a card payment for an order and returns its id. */
  String cardPayment(String last4, long amount, boolean manual, String reference) throws IOException {
    String id = newId();
    Answer answer = card("POST", "/payments", createBody(id, amount, token(last4), manual, reference));
    if (answer.status() != 201) {
      throw new AssertionError("create answered " + answer);
    }
    return id;
  }

  static String checkoutBody(String amount, String mizanId, String reference) {
    return "{\"payment\":{\"amount\":\"" + amount + "\",\"currency\":\"SAR\",\"order\":{\"reference_id\":\"" + mizanId + "\"},"
        + "\"meta\":{\"order_id\":\"" + reference + "\"}},\"merchant_urls\":{\"success\":\"" + RETURN_URL + "\"}}";
  }

  /** Creates a pay-later session for an order and returns the provider's payment id. */
  String payLaterPayment(String amount, String reference) throws IOException {
    Answer answer = payLater("POST", "/checkout", checkoutBody(amount, newId(), reference));
    if (answer.status() != 200) {
      throw new AssertionError("checkout answered " + answer);
    }
    return answer.json().path("payment").path("id").asString();
  }

  // ---------------------------------------------------------------- time

  /** Moves the simulator's clock and runs what became due. */
  void advance(Duration by) {
    clock.advance(by);
    simulator.runDueTasks();
  }

  /** Waits a short real time for something the simulator does in the background. */
  static void await(BooleanSupplier condition) {
    long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > end) {
        throw new AssertionError("The condition did not become true in 5 seconds.");
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }

  /** Gives the background thread time to do something it should NOT do. */
  static void settle() {
    try {
      Thread.sleep(150);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
