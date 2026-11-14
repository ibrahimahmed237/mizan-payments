package dev.mizan.app.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A small HTTP client for Mizan's API, as the store's backend would use it. */
public final class Api {

  /** An answer of the API. */
  public record Answer(int status, HttpResponse<String> raw) {

    public JsonNode json() {
      return JSON.readTree(raw.body());
    }

    /** A field of the answer as text; null when it is missing or null. */
    public String text(String field) {
      return json().path(field).asString(null);
    }

    public long number(String field) {
      return json().path(field).asLong();
    }

    /** The error code of a problem answer. */
    public String code() {
      return text("code");
    }

    public String header(String name) {
      return raw.headers().firstValue(name).orElse(null);
    }
  }

  public static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  private final String baseUrl;
  private final String key;

  public Api(String baseUrl, String key) {
    this.baseUrl = baseUrl;
    this.key = key;
  }

  /** The same API with another key; null sends no key at all. */
  public Api withKey(String otherKey) {
    return new Api(baseUrl, otherKey);
  }

  public Answer get(String path) {
    return send("GET", path, null, null);
  }

  /** A POST with a new Idempotency-Key. */
  public Answer post(String path, Object body) {
    return send("POST", path, body, UUID.randomUUID().toString());
  }

  public Answer post(String path, Object body, String idempotencyKey) {
    return send("POST", path, body, idempotencyKey);
  }

  /**
   * @param body a map or a record written as JSON, a string sent as it is, or null for no body
   * @param idempotencyKey null sends no Idempotency-Key header
   */
  public Answer send(String method, String path, Object body, String idempotencyKey) {
    var request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30));
    if (key != null) {
      request.header("Authorization", "Bearer " + key);
    }
    if (idempotencyKey != null) {
      request.header("Idempotency-Key", idempotencyKey);
    }
    if (body == null) {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      request.header("Content-Type", "application/json");
      request.method(method, HttpRequest.BodyPublishers.ofString(body instanceof String text ? text : JSON.writeValueAsString(body)));
    }
    try {
      HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
      return new Answer(response.statusCode(), response);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** A map in the order given: {@code body("amount", 15000, "currency", "SAR")}. A null value leaves the field out. */
  public static Map<String, Object> body(Object... namesAndValues) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < namesAndValues.length; i += 2) {
      if (namesAndValues[i + 1] != null) {
        map.put((String) namesAndValues[i], namesAndValues[i + 1]);
      }
    }
    return map;
  }
}
