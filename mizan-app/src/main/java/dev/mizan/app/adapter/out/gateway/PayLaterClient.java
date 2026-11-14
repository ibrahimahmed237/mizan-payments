package dev.mizan.app.adapter.out.gateway;

import dev.mizan.app.adapter.out.gateway.GatewayHttp.Answer;
import dev.mizan.app.application.port.GatewayClient;
import dev.mizan.app.application.port.GatewayResult;
import dev.mizan.app.application.port.GatewayResult.Done;
import dev.mizan.app.application.port.GatewayResult.Unknown;
import dev.mizan.core.money.Money;
import dev.mizan.core.payment.GatewayStatus;
import dev.mizan.core.sync.Snapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * The pay-later provider. It follows the public documents of a real provider; the simulator follows the same ones.
 * Amounts are riyals as text, such as {@code "150.00"}. The provider chooses the payment's id.
 */
final class PayLaterClient implements GatewayClient {

  private final GatewayHttp http;

  PayLaterClient(GatewayHttp http) {
    this.http = http;
  }

  @Override
  public GatewayResult create(Create request) {
    Map<String, Object> payment = new LinkedHashMap<>();
    payment.put("amount", Money.formatRiyals(request.amount()));
    payment.put("currency", request.currency());
    if (request.description() != null) {
      payment.put("description", request.description());
    }
    // Mizan's payment id is the provider's order reference, so a payment found there can be tied to Mizan's.
    payment.put("order", Map.of("reference_id", request.paymentId().toString()));
    payment.put("meta", Map.of("order_id", request.reference()));
    Map<String, Object> urls = Map.of(
        "success", request.callbackUrl(), "cancel", request.callbackUrl(), "failure", request.callbackUrl());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("payment", payment);
    body.put("merchant_urls", urls);

    Answer answer = http.send("POST", "/checkout", body, Map.of());
    if (!GatewayHttp.success(answer)) {
      return GatewayHttp.failure(answer);
    }
    try {
      JsonNode json = GatewayHttp.read(answer.body());
      JsonNode session = json == null ? null : json.get("payment");
      String id = session == null ? null : session.path("id").asString(null);
      if (id == null) {
        return new Unknown("unreadable_answer");
      }
      String page = json.path("configuration").path("available_products").path("installments").path(0).path("web_url").asString(null);
      Snapshot snapshot = normalize(session, id);
      return new Done(snapshot.status() == GatewayStatus.INITIATED ? snapshot.withActionUrl(page) : snapshot, id);
    } catch (RuntimeException e) {
      // A body Mizan cannot read, or a record that cannot be true, says nothing. Guessing is how money is lost.
      return new Unknown("unreadable_answer");
    }
  }

  @Override
  public GatewayResult fetch(String gatewayPaymentId) {
    Answer answer = http.send("GET", "/payments/" + gatewayPaymentId, null, Map.of());
    if (answer != null && answer.status() == 404) {
      return new Done(Snapshot.notFound(), gatewayPaymentId);
    }
    return result(answer, gatewayPaymentId);
  }

  @Override
  public GatewayResult capture(String gatewayPaymentId, long amount) {
    Map<String, Object> body = Map.of("amount", Money.formatRiyals(amount));
    return result(http.send("POST", "/payments/" + gatewayPaymentId + "/captures", body, Map.of()), gatewayPaymentId);
  }

  @Override
  public GatewayResult cancel(String gatewayPaymentId) {
    return result(http.send("POST", "/payments/" + gatewayPaymentId + "/close", Map.of(), Map.of()), gatewayPaymentId);
  }

  @Override
  public GatewayResult refund(String gatewayPaymentId, long amount, String refundKey) {
    // The key makes the same refund sent twice happen once. Only so may Mizan send a refund again by itself.
    Map<String, Object> body = Map.of("amount", Money.formatRiyals(amount), "reference_id", refundKey);
    return result(http.send("POST", "/payments/" + gatewayPaymentId + "/refunds", body, Map.of()), gatewayPaymentId);
  }

  private static GatewayResult result(Answer answer, String id) {
    if (!GatewayHttp.success(answer)) {
      return GatewayHttp.failure(answer);
    }
    try {
      return new Done(normalize(GatewayHttp.read(answer.body()), id), id);
    } catch (RuntimeException e) {
      // A body Mizan cannot read, or a record that cannot be true, says nothing. Guessing is how money is lost.
      return new Unknown("unreadable_answer");
    }
  }

  /** The provider's payment object in Mizan's one shape. Throws when the object cannot be this payment's record. */
  static Snapshot normalize(JsonNode json, String expectedId) {
    if (json == null || !expectedId.equals(json.path("id").asString(null))) {
      throw new IllegalArgumentException("not this payment's record");
    }
    long amount = riyals(json.path("amount"));
    String currency = json.path("currency").asString(null);
    long captured = sum(json.path("captures"));
    long refunded = sum(json.path("refunds"));
    String status = json.path("status").asString("");

    if (captured > 0) {
      // Money was taken. "Closed" after a capture through Mizan; still "authorized" after a part capture made at
      // the provider itself. In any other status, captured money cannot be true.
      if (!status.equals("CLOSED") && !status.equals("AUTHORIZED")) {
        throw new IllegalArgumentException("money captured in a status without money");
      }
      return Snapshot.paid(amount, currency, captured, refunded);
    }
    return switch (status) {
      case "CREATED" -> Snapshot.of(GatewayStatus.INITIATED, amount, currency);
      case "AUTHORIZED" -> Snapshot.of(GatewayStatus.AUTHORIZED, amount, currency);
      case "CLOSED" -> Snapshot.of(GatewayStatus.VOIDED, amount, currency); // released with nothing taken
      case "REJECTED" -> Snapshot.of(GatewayStatus.FAILED, amount, currency).withFailure("declined", null);
      case "EXPIRED" -> Snapshot.of(GatewayStatus.EXPIRED, amount, currency);
      default -> throw new IllegalArgumentException("unknown status");
    };
  }

  private static long sum(JsonNode items) {
    long total = 0;
    for (JsonNode item : items) {
      total = Math.addExact(total, riyals(item.path("amount")));
    }
    return total;
  }

  /** Riyals as text, such as "150.00". A number is refused: 150 could mean riyals or halalas. */
  private static long riyals(JsonNode value) {
    if (!value.isString()) {
      throw new IllegalArgumentException("an amount that is not text");
    }
    return Money.parseRiyals(value.asString());
  }
}
