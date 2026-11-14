package dev.mizan.app.adapter.in.web;

import dev.mizan.app.application.Problem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

/**
 * The {@code Idempotency-Key} header and the hash that tells a repeat of a request from another request.
 *
 * <p>The hash covers the method, the path and the JSON body with object keys in order, so the same body written
 * with another key order or other spaces is still the same request. Only the hash is stored, never the body.
 */
final class RequestHash {

  private RequestHash() {}

  /** The key, or a problem when it is missing or not 1 to 128 printable characters. */
  static String key(String header) {
    if (header == null || header.isBlank()) {
      throw new Problem(Problem.Kind.BAD_REQUEST, "idempotency_key_required", "This request needs an Idempotency-Key header.");
    }
    if (header.length() > 128 || !header.chars().allMatch(c -> c > 32 && c < 127)) {
      throw new Problem(
          Problem.Kind.BAD_REQUEST, "invalid_idempotency_key", "The Idempotency-Key is 1 to 128 printable characters, without spaces.");
    }
    return header;
  }

  static String of(String method, String path, JsonNode body) {
    var text = new StringBuilder(method).append('\n').append(path).append('\n');
    canonical(body, text);
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void canonical(JsonNode node, StringBuilder out) {
    if (node == null || node.isMissingNode()) {
      out.append("{}"); // no body and an empty object are the same request
    } else if (node.isObject()) {
      var sorted = new TreeMap<String, JsonNode>();
      node.properties().forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
      out.append('{');
      sorted.forEach((name, value) -> {
        out.append('"').append(name).append("\":");
        canonical(value, out);
        out.append(',');
      });
      out.append('}');
    } else if (node.isArray()) {
      out.append('[');
      node.forEach(item -> {
        canonical(item, out);
        out.append(',');
      });
      out.append(']');
    } else {
      out.append(node.toString());
    }
  }
}
