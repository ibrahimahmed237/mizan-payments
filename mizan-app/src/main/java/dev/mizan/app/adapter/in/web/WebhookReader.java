package dev.mizan.app.adapter.in.web;

import dev.mizan.app.application.Problem;
import dev.mizan.app.application.ReceiveWebhook.Event;
import dev.mizan.core.payment.Gateway;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks that a webhook really comes from its gateway, and takes from it the little that Mizan uses: which event,
 * about which payment. The status in a webhook is never used (rule R2).
 *
 * <p>Each gateway proves itself in its own way. The card gateway puts a shared secret inside the body. The
 * pay-later provider signs the body: {@code X-Signature} is the hex HMAC-SHA256 of the body with the shared secret.
 */
public final class WebhookReader {

  /** The secret shared with each gateway. */
  public record Secrets(String cardGateway, String payLater) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Secrets secrets;

  public WebhookReader(Secrets secrets) {
    this.secrets = secrets;
  }

  Event card(byte[] body) {
    JsonNode json = parse(body);
    // No secret, a wrong secret and a body that is not JSON all get the same answer: nothing is given away.
    if (json == null || !same(json.path("secret_token").asString(""), secrets.cardGateway())) {
      throw unauthorized();
    }
    String eventId = text(json.path("id"));
    String type = text(json.path("type"));
    String paymentId = text(json.path("data").path("id"));
    return new Event(Gateway.CARD_GATEWAY, eventId, type, paymentId, uuid(json.path("data").path("metadata").path("mizan_payment_id")));
  }

  Event payLater(byte[] body, String signature) {
    if (signature == null || !same(signature, hmac(secrets.payLater(), body))) {
      throw unauthorized();
    }
    JsonNode json = parse(body);
    if (json == null) {
      throw malformed();
    }
    // The provider sends no event id. The body's hash stands in for it: the same webhook sent again is the same event.
    String eventId = "sha256:" + HexFormat.of().formatHex(sha256(body));
    return new Event(Gateway.PAY_LATER, eventId, text(json.path("status")), text(json.path("id")), uuid(json.path("order").path("reference_id")));
  }

  private static JsonNode parse(byte[] body) {
    try {
      JsonNode json = JSON.readTree(body);
      return json != null && json.isObject() ? json : null;
    } catch (JacksonException e) {
      return null;
    }
  }

  /** A text field that must be there. Its length is limited, because it is stored. */
  private static String text(JsonNode node) {
    String value = node.isString() ? node.asString() : null;
    if (value == null || value.isBlank() || value.length() > 255) {
      throw malformed();
    }
    return value;
  }

  /** Mizan's own payment id, if the gateway carries one that is a UUID. */
  private static UUID uuid(JsonNode node) {
    try {
      return node.isString() ? UUID.fromString(node.asString()) : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** Compares in constant time, so the time it takes does not tell how much of a guess was right. */
  private static boolean same(String given, String expected) {
    return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
  }

  static String hmac(String secret, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(body));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] sha256(byte[] body) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(body);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Problem unauthorized() {
    return new Problem(Problem.Kind.UNAUTHORIZED, "unauthorized", "The webhook could not be checked.");
  }

  private static Problem malformed() {
    return new Problem(Problem.Kind.BAD_REQUEST, "malformed_body", "The webhook does not say which event it is or which payment it is about.");
  }
}
