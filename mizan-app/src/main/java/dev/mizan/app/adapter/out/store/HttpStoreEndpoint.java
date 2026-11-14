package dev.mizan.app.adapter.out.store;

import dev.mizan.app.application.port.StoreEndpoint;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Posts events to the store's address. Each request is signed: {@code Mizan-Signature: v1=<hex HMAC-SHA256 of
 * timestamp + "." + body>}, with the secret shared with the store. The store checks the signature and refuses a
 * timestamp older than five minutes.
 */
public final class HttpStoreEndpoint implements StoreEndpoint {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final URI address;
  private final String secret;
  private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build();

  /** @param address the store's address for events; null or empty when none is set */
  public HttpStoreEndpoint(String address, String secret) {
    this.address = address == null || address.isBlank() ? null : URI.create(address);
    this.secret = secret;
  }

  @Override
  public boolean configured() {
    return address != null;
  }

  @Override
  public Delivery send(UUID eventId, long timestamp, String body) {
    var request = HttpRequest.newBuilder(address)
        .timeout(TIMEOUT)
        .header("Content-Type", "application/json")
        .header("Mizan-Event-Id", eventId.toString())
        .header("Mizan-Timestamp", Long.toString(timestamp))
        .header("Mizan-Signature", "v1=" + sign(secret, timestamp, body))
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build();
    try {
      int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
      return status >= 200 && status < 300 ? new Delivery(true, null) : new Delivery(false, "http_" + status);
    } catch (IOException e) {
      return new Delivery(false, "no_answer: " + e.getClass().getSimpleName());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Delivery(false, "interrupted");
    }
  }

  /** The signature of one request. The store computes the same and compares. */
  public static String sign(String secret, long timestamp, String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException(e);
    }
  }
}
