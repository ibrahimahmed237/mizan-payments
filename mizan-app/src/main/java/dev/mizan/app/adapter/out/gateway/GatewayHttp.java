package dev.mizan.app.adapter.out.gateway;

import dev.mizan.app.application.port.GatewayResult;
import dev.mizan.app.application.port.GatewayResult.Refused;
import dev.mizan.app.application.port.GatewayResult.Unknown;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One HTTP call to a gateway, sent once. It turns everything that is not a readable answer into
 * {@link Unknown}: a timeout, a broken connection, a server error. It never sends again by itself.
 */
final class GatewayHttp {

  /** An HTTP answer that arrived. */
  record Answer(int status, String body) {}

  private static final Logger LOG = LoggerFactory.getLogger(GatewayHttp.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final String gateway;
  private final String baseUrl;
  private final String authorization;
  private final Duration timeout;
  private final HttpClient client;

  GatewayHttp(String gateway, String baseUrl, String authorization, Duration connectTimeout, Duration timeout) {
    this.gateway = gateway;
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.authorization = authorization;
    this.timeout = timeout;
    this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connectTimeout).build();
  }

  /** @return the answer, or null when none arrived in time */
  Answer send(String method, String path, Object body, Map<String, String> headers) {
    long started = System.nanoTime();
    var request = HttpRequest.newBuilder(URI.create(baseUrl + path))
        .timeout(timeout)
        .header("Authorization", authorization)
        .header("Accept", "application/json");
    headers.forEach(request::header);
    if (body == null) {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      request.header("Content-Type", "application/json");
      request.method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8));
    }
    try {
      HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      // The path has ids only. Bodies and headers are never logged: they carry tokens and keys.
      LOG.info("gateway={} {} {} -> {} in {} ms", gateway, method, path, response.statusCode(), millisSince(started));
      return new Answer(response.statusCode(), response.body());
    } catch (IOException e) {
      LOG.warn("gateway={} {} {} -> no answer in {} ms: {}", gateway, method, path, millisSince(started), e.getClass().getSimpleName());
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  /**
   * What an answer that is not a success means. A clear "no" to this request is {@link Refused}. Everything else is
   * {@link Unknown}: the gateway's own trouble, or Mizan's key being refused, says nothing about the payment.
   */
  static GatewayResult failure(Answer answer) {
    if (answer == null) {
      return new Unknown("no_answer");
    }
    int status = answer.status();
    if (status >= 400 && status < 500 && status != 401 && status != 403 && status != 408 && status != 429) {
      return new Refused("gateway_refused", message(answer.body()));
    }
    return new Unknown("http_" + status);
  }

  /** The body as JSON, or null when it is not JSON. */
  static JsonNode read(String body) {
    try {
      return body == null || body.isBlank() ? null : JSON.readTree(body);
    } catch (JacksonException e) {
      return null;
    }
  }

  static boolean success(Answer answer) {
    return answer != null && answer.status() >= 200 && answer.status() < 300;
  }

  /** The gateway's own words for a refusal, cut to a safe length. */
  private static String message(String body) {
    JsonNode json = read(body);
    String text = json == null ? null : json.path("message").asString(null);
    if (text == null) {
      return null;
    }
    return text.length() > 255 ? text.substring(0, 255) : text;
  }

  private static long millisSince(long started) {
    return (System.nanoTime() - started) / 1_000_000;
  }
}
