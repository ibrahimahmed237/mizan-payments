package dev.mizan.app.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Plays the store's backend: it receives Mizan's events, keeps them, and answers as a test tells it to. */
public final class StoreReceiver {

  /** One request as it arrived. */
  public record Received(String eventId, String timestamp, String signature, String body) {}

  public static final String SECRET = "store-event-secret";

  public final List<Received> received = new CopyOnWriteArrayList<>();
  /** The answer to every request; 200 unless a test changes it. */
  public final AtomicInteger status = new AtomicInteger(200);
  private final HttpServer server;

  StoreReceiver() {
    try {
      server = HttpServer.create(new InetSocketAddress(0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext("/", exchange -> {
      try (exchange) {
        var headers = exchange.getRequestHeaders();
        received.add(new Received(
            headers.getFirst("Mizan-Event-Id"), headers.getFirst("Mizan-Timestamp"), headers.getFirst("Mizan-Signature"),
            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        exchange.sendResponseHeaders(status.get(), -1);
      }
    });
    server.start();
  }

  public String url() {
    return "http://localhost:" + server.getAddress().getPort() + "/mizan-events";
  }

  /** The requests that carried this event, in the order they came. */
  public List<Received> of(String eventId) {
    return received.stream().filter(request -> eventId.equals(request.eventId())).toList();
  }
}
