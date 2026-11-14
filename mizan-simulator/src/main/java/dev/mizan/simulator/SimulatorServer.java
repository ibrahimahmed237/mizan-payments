package dev.mizan.simulator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mizan.core.money.Money;
import dev.mizan.core.payment.Gateway;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The simulator over HTTP: the card gateway under {@code /card}, the pay-later provider under {@code /paylater}, and
 * the control calls under {@code /_sim}. Call faults are applied here, around the work.
 */
public final class SimulatorServer implements AutoCloseable {

  /** An HTTP answer, or the instruction to drop the connection without one. */
  private record Reply(int status, String contentType, String body, boolean drop, URI redirect) {
    static Reply json(int status, JsonNode body) {
      return new Reply(status, "application/json", body.toString(), false, null);
    }

    static Reply text(int status, String contentType, String body) {
      return new Reply(status, contentType, body, false, null);
    }

    static Reply dropConnection() {
      return new Reply(0, null, null, true, null);
    }

    static Reply redirect(URI to) {
      return new Reply(302, null, "", false, to);
    }
  }

  static {
    // The JDK's small server writes an answer's headers and body apart. Without this, the second part waits about
    // 40 ms for the operating system (Nagle's algorithm), on every call. It is read once, before the first server.
    if (System.getProperty("sun.net.httpserver.nodelay") == null) {
      System.setProperty("sun.net.httpserver.nodelay", "true");
    }
  }

  private final Simulator simulator;
  private final HttpServer server;
  private final ExecutorService workers = Executors.newCachedThreadPool(runnable -> daemon(runnable, "simulator-http"));
  private final ScheduledExecutorService ticker =
      Executors.newSingleThreadScheduledExecutor(runnable -> daemon(runnable, "simulator-clock"));

  private SimulatorServer(Simulator simulator, HttpServer server) {
    this.simulator = simulator;
    this.server = server;
  }

  /** Starts the simulator on a port; 0 picks a free one. */
  public static SimulatorServer start(Simulator simulator, int port) {
    try {
      HttpServer http = HttpServer.create(new InetSocketAddress(port), 0);
      var started = new SimulatorServer(simulator, http);
      http.createContext("/", started::handle);
      http.setExecutor(started.workers);
      http.start();
      simulator.baseUrl("http://localhost:" + http.getAddress().getPort());
      started.ticker.scheduleWithFixedDelay(started::tick, 20, 20, TimeUnit.MILLISECONDS);
      return started;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public Simulator simulator() {
    return simulator;
  }

  public String baseUrl() {
    return simulator.baseUrl();
  }

  @Override
  public void close() {
    ticker.shutdownNow();
    server.stop(0);
    workers.shutdownNow();
  }

  private void tick() {
    try {
      simulator.runDueTasks();
    } catch (RuntimeException e) {
      // A failed task must not stop the clock thread.
    }
  }

  private static Thread daemon(Runnable runnable, String name) {
    Thread thread = new Thread(runnable, name);
    thread.setDaemon(true);
    return thread;
  }

  // ---------------------------------------------------------------- routing

  private void handle(HttpExchange exchange) throws IOException {
    Reply reply;
    try {
      reply = route(exchange);
    } catch (SimError e) {
      ObjectNode body = Wire.JSON.createObjectNode();
      body.put("type", e.type());
      body.put("message", e.getMessage());
      reply = Reply.json(e.status(), body);
    } catch (JacksonException | IllegalArgumentException e) {
      ObjectNode body = Wire.JSON.createObjectNode();
      body.put("type", "invalid_request_error");
      body.put("message", "The request cannot be read.");
      reply = Reply.json(400, body);
    }
    try (exchange) {
      if (reply.drop()) {
        return; // closing without an answer: the caller sees a broken connection
      }
      if (reply.redirect() != null) {
        exchange.getResponseHeaders().set("Location", reply.redirect().toString());
      }
      byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
      if (reply.contentType() != null) {
        exchange.getResponseHeaders().set("Content-Type", reply.contentType());
      }
      exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
      if (bytes.length > 0) {
        exchange.getResponseBody().write(bytes);
      }
    }
  }

  private Reply route(HttpExchange exchange) throws IOException {
    String method = exchange.getRequestMethod();
    String[] path = exchange.getRequestURI().getPath().split("/");
    // path[0] is empty: "/card/v1/payments/{id}/refund" -> ["", "card", "v1", "payments", id, "refund"]
    String area = path.length > 1 ? path[1] : "";
    return switch (area) {
      case "card" -> card(exchange, method, path);
      case "paylater" -> payLater(exchange, method, path);
      case "_sim" -> control(exchange, method, path);
      case "health" -> Reply.text(200, "text/plain", "ok");
      default -> notFound();
    };
  }

  private Reply card(HttpExchange exchange, String method, String[] path) throws IOException {
    if (path.length == 4 && path[2].equals("3ds")) {
      return customerPage(exchange, method, path[3]);
    }
    if (path.length < 4 || !path[2].equals("v1")) {
      return notFound();
    }
    String key = simulator.config().cardKey();
    String basic = "Basic " + Base64.getEncoder().encodeToString((key + ":").getBytes(StandardCharsets.UTF_8));
    if (!basic.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
      return error(401, "authentication_error", "Invalid authorization credentials");
    }
    String resource = path[3];
    if (resource.equals("tokens") && method.equals("POST")) {
      JsonNode body = body(exchange);
      String token = simulator.createToken(text(body, "number"), text(body, "scheme"), "applepay".equals(text(body, "type")));
      ObjectNode answer = Wire.JSON.createObjectNode();
      answer.put("id", token);
      return Reply.json(201, answer);
    }
    if (resource.equals("settlements") && path.length == 5 && method.equals("GET")) {
      return Reply.text(200, "text/csv", simulator.closePayout(Gateway.CARD_GATEWAY, LocalDate.parse(path[4])));
    }
    if (!resource.equals("payments")) {
      return notFound();
    }
    if (path.length == 4 && method.equals("POST")) {
      JsonNode body = body(exchange);
      String reference = body.path("metadata").path("reference").asString("");
      return withFault(reference, Call.CREATE, () -> {
        SimPayment p = simulator.cardCreate(
            text(body, "given_id"), body.path("amount").asLong(0), text(body, "currency"),
            body.path("source").path("token").asString(null), body.path("manual").asBoolean(false), text(body, "callback_url"),
            reference, body.path("metadata").path("mizan_payment_id").asString(null));
        return Reply.json(201, Wire.card(p, simulator.baseUrl(), false));
      });
    }
    if (path.length < 5) {
      return notFound();
    }
    SimPayment known = simulator.payment(path[4]);
    String reference = known == null ? "" : known.reference;
    if (path.length == 5 && method.equals("GET")) {
      if (simulator.fetchIsDown(reference)) {
        return error(503, "api_error", "Service unavailable");
      }
      return withFault(reference, Call.FETCH, () -> {
        SimPayment p = simulator.payment(path[4]);
        if (p == null || p.gateway != Gateway.CARD_GATEWAY) {
          return error(404, "record_not_found", "The payment record you were looking for was not found.");
        }
        return Reply.json(200, Wire.card(p, simulator.baseUrl(), simulator.has(p.reference, Fault.AMOUNT_CHANGED)));
      });
    }
    if (path.length == 6 && method.equals("POST")) {
      JsonNode body = body(exchange);
      Long amount = body.has("amount") ? body.path("amount").asLong() : null;
      return switch (path[5]) {
        case "capture" -> withFault(reference, Call.CAPTURE, () -> cardReply(simulator.cardCapture(path[4], amount)));
        case "void" -> withFault(reference, Call.VOID, () -> cardReply(simulator.cardVoid(path[4])));
        case "refund" -> withFault(reference, Call.REFUND, () -> cardReply(simulator.cardRefund(path[4], amount)));
        default -> notFound();
      };
    }
    return notFound();
  }

  private Reply cardReply(SimPayment p) {
    return Reply.json(200, Wire.card(p, simulator.baseUrl(), false));
  }

  private Reply payLater(HttpExchange exchange, String method, String[] path) throws IOException {
    if (path.length == 4 && path[2].equals("checkout")) {
      return customerPage(exchange, method, path[3]);
    }
    if (path.length < 4 || !path[2].equals("v2")) {
      return notFound();
    }
    if (!("Bearer " + simulator.config().payLaterKey()).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
      return error(401, "unauthorized", "The API key is not valid.");
    }
    if (path[3].equals("settlements") && path.length == 5 && method.equals("GET")) {
      return Reply.text(200, "text/csv", simulator.closePayout(Gateway.PAY_LATER, LocalDate.parse(path[4])));
    }
    if (path[3].equals("checkout") && path.length == 4 && method.equals("POST")) {
      JsonNode body = body(exchange);
      JsonNode payment = body.path("payment");
      String reference = payment.path("meta").path("order_id").asString("");
      return withFault(reference, Call.CREATE, () -> {
        SimPayment p = simulator.payLaterCheckout(
            Money.parseRiyals(payment.path("amount").asString("")), payment.path("currency").asString(null),
            payment.path("order").path("reference_id").asString(null), reference,
            body.path("merchant_urls").path("success").asString(null));
        ObjectNode answer = Wire.JSON.createObjectNode();
        answer.put("id", "session_" + p.id);
        answer.put("status", "created");
        answer.set("payment", Wire.payLater(p, false));
        answer.putObject("configuration").putObject("available_products").putArray("installments").addObject()
            .put("web_url", simulator.baseUrl() + "/paylater/checkout/" + p.id);
        return Reply.json(200, answer);
      });
    }
    if (!path[3].equals("payments") || path.length < 5) {
      return notFound();
    }
    SimPayment known = simulator.payment(path[4]);
    String reference = known == null ? "" : known.reference;
    if (path.length == 5 && method.equals("GET")) {
      if (simulator.fetchIsDown(reference)) {
        return error(503, "unavailable", "Service unavailable");
      }
      return withFault(reference, Call.FETCH, () -> {
        SimPayment p = simulator.payment(path[4]);
        if (p == null || p.gateway != Gateway.PAY_LATER) {
          return error(404, "not_found", "No such payment.");
        }
        return Reply.json(200, Wire.payLater(p, simulator.has(p.reference, Fault.AMOUNT_CHANGED)));
      });
    }
    if (path.length == 6 && method.equals("POST")) {
      JsonNode body = body(exchange);
      return switch (path[5]) {
        case "captures" -> withFault(reference, Call.CAPTURE,
            () -> Reply.json(200, Wire.payLater(simulator.payLaterCapture(path[4], Money.parseRiyals(body.path("amount").asString(""))), false)));
        case "close" -> withFault(reference, Call.VOID, () -> Reply.json(200, Wire.payLater(simulator.payLaterClose(path[4]), false)));
        case "refunds" -> withFault(reference, Call.REFUND, () -> Reply.json(200, Wire.payLater(
            simulator.payLaterRefund(path[4], Money.parseRiyals(body.path("amount").asString("")), body.path("reference_id").asString(null)), false)));
        default -> notFound();
      };
    }
    return notFound();
  }

  /** The bank's page or the provider's page: GET shows two links, POST with {@code result} acts and redirects. */
  private Reply customerPage(HttpExchange exchange, String method, String id) {
    if (simulator.payment(id) == null) {
      return notFound();
    }
    String query = exchange.getRequestURI().getQuery();
    if (method.equals("GET") && query == null) {
      String page = "<!doctype html><title>Simulator</title><h1>Simulated bank page</h1>"
          + "<form method=post action='?result=approve'><button>Approve</button></form>"
          + "<form method=post action='?result=decline'><button>Decline</button></form>";
      return Reply.text(200, "text/html; charset=utf-8", page);
    }
    return Reply.redirect("result=decline".equals(query) ? simulator.customerDeclines(id) : simulator.customerApproves(id));
  }

  /** The control calls. No authentication: the simulator is a test tool. */
  private Reply control(HttpExchange exchange, String method, String[] path) throws IOException {
    if (path.length != 3 || !method.equals("POST")) {
      return notFound();
    }
    JsonNode body = body(exchange);
    ObjectNode done = Wire.JSON.createObjectNode();
    done.put("ok", true);
    switch (path[2]) {
      case "fault" -> {
        Fault fault = Fault.valueOf(text(body, "fault"));
        if (fault.sticky()) {
          simulator.stick(text(body, "reference"), fault);
        } else {
          simulator.plan(text(body, "reference"), Call.valueOf(text(body, "call")), fault);
        }
      }
      case "file-fault" -> simulator.planFile(FileFault.valueOf(text(body, "fault")), text(body, "reference"));
      case "customer" -> {
        switch (String.valueOf(text(body, "action"))) {
          case "approve" -> done.put("redirect", simulator.customerApproves(text(body, "id")).toString());
          case "decline" -> done.put("redirect", simulator.customerDeclines(text(body, "id")).toString());
          case "leave" -> simulator.customerLeaves(text(body, "id"));
          default -> throw new IllegalArgumentException("action");
        }
      }
      case "external-refund" -> simulator.externalRefund(text(body, "id"), body.path("amount").asLong());
      case "webhook-target" -> simulator.webhookTarget(Gateway.valueOf(text(body, "gateway")), URI.create(text(body, "url")));
      case "clock" -> {
        if (!(simulator.clock() instanceof MutableClock clock)) {
          throw new SimError(409, "real_clock", "This simulator runs on the real clock.");
        }
        clock.advance(Duration.ofSeconds(body.path("advance_seconds").asLong()));
        simulator.runDueTasks();
      }
      default -> {
        return notFound();
      }
    }
    return Reply.json(200, done);
  }

  // ---------------------------------------------------------------- faults around a call

  private Reply withFault(String reference, Call call, Supplier<Reply> work) {
    Fault fault = simulator.takeFault(reference, call);
    if (fault == null) {
      return work.get();
    }
    switch (fault) {
      case LOST_ANSWER -> {
        work.get();
        return Reply.dropConnection();
      }
      case TIMEOUT_BEFORE -> {
        hang();
        return Reply.dropConnection();
      }
      case SLOW_APPLY, VERY_SLOW_APPLY -> {
        simulator.schedule(Duration.ofSeconds(fault == Fault.SLOW_APPLY ? 5 : 120), () -> quietly(work));
        hang();
        return Reply.dropConnection();
      }
      case ERROR_500 -> {
        return error(500, "api_error", "Internal error");
      }
      case ERROR_500_AFTER -> {
        work.get();
        return error(500, "api_error", "Internal error");
      }
      case GARBAGE -> {
        work.get();
        return Reply.text(200, "text/html", "<html><body>Bad gateway</body></html>");
      }
      default -> throw new IllegalStateException(fault + " is not a call fault.");
    }
  }

  private static void quietly(Supplier<Reply> work) {
    try {
      work.get();
    } catch (SimError e) {
      // The delayed work was refused. Nobody is waiting for the answer.
    }
  }

  private void hang() {
    try {
      Thread.sleep(simulator.config().hang().toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // ---------------------------------------------------------------- small helpers

  private static JsonNode body(HttpExchange exchange) throws IOException {
    byte[] bytes = exchange.getRequestBody().readAllBytes();
    return bytes.length == 0 ? Wire.JSON.createObjectNode() : Wire.JSON.readTree(bytes);
  }

  private static String text(JsonNode body, String field) {
    return body.path(field).asString(null);
  }

  private static Reply error(int status, String type, String message) {
    ObjectNode body = Wire.JSON.createObjectNode();
    body.put("type", type);
    body.put("message", message);
    return Reply.json(status, body);
  }

  private static Reply notFound() {
    return error(404, "record_not_found", "No such resource.");
  }
}
