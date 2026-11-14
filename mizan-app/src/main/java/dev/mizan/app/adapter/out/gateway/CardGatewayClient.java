package dev.mizan.app.adapter.out.gateway;

import dev.mizan.app.adapter.out.gateway.GatewayHttp.Answer;
import dev.mizan.app.application.port.GatewayClient;
import dev.mizan.app.application.port.GatewayResult;
import dev.mizan.app.application.port.GatewayResult.Done;
import dev.mizan.app.application.port.GatewayResult.Unknown;
import dev.mizan.core.payment.GatewayStatus;
import dev.mizan.core.sync.Snapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * The card gateway: cards and wallets. It follows the public documents of a real Saudi card gateway; the simulator
 * follows the same ones. Amounts are whole halalas.
 */
final class CardGatewayClient implements GatewayClient {

  private final GatewayHttp http;

  CardGatewayClient(GatewayHttp http) {
    this.http = http;
  }

  @Override
  public GatewayResult create(Create request) {
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("type", "token");
    source.put("token", request.token());
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("reference", request.reference());
    metadata.put("mizan_payment_id", request.paymentId().toString());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("given_id", request.paymentId().toString()); // the id is Mizan's: the gateway creates it at most once
    body.put("amount", request.amount());
    body.put("currency", request.currency());
    if (request.description() != null) {
      body.put("description", request.description());
    }
    body.put("manual", request.manualCapture());
    body.put("callback_url", request.callbackUrl());
    body.put("source", source);
    body.put("metadata", metadata);

    String id = request.paymentId().toString();
    Answer answer = http.send("POST", "/payments", body, Map.of());
    if (answer != null && answer.status() == 409) {
      return fetch(id); // "this id exists": the payment is there, so its record is the answer
    }
    return result(answer, id);
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
    return result(http.send("POST", "/payments/" + gatewayPaymentId + "/capture", Map.of("amount", amount), Map.of()), gatewayPaymentId);
  }

  @Override
  public GatewayResult cancel(String gatewayPaymentId) {
    return result(http.send("POST", "/payments/" + gatewayPaymentId + "/void", Map.of(), Map.of()), gatewayPaymentId);
  }

  @Override
  public GatewayResult refund(String gatewayPaymentId, long amount, String refundKey) {
    // This gateway takes no refund key (assumption A3). So Mizan never sends a refund to it twice by itself.
    return result(http.send("POST", "/payments/" + gatewayPaymentId + "/refund", Map.of("amount", amount), Map.of()), gatewayPaymentId);
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

  /** The gateway's payment object in Mizan's one shape. Throws when the object cannot be this payment's record. */
  static Snapshot normalize(JsonNode json, String expectedId) {
    if (json == null || !expectedId.equals(json.path("id").asString(null))) {
      throw new IllegalArgumentException("not this payment's record");
    }
    long amount = whole(json, "amount");
    String currency = json.path("currency").asString(null);
    JsonNode source = json.path("source");
    String status = json.path("status").asString("");

    long captured = whole(json, "captured");
    long refunded = whole(json, "refunded");

    Snapshot snapshot = switch (status) {
      case "initiated" -> noMoney(GatewayStatus.INITIATED, amount, currency, captured)
          .withActionUrl(source.path("transaction_url").asString(null));
      case "authorized" -> noMoney(GatewayStatus.AUTHORIZED, amount, currency, captured);
      // "Paid" is a purchase taken in full, whatever the totals say.
      case "paid" -> Snapshot.paid(amount, currency, amount, refunded);
      case "captured", "refunded" -> Snapshot.paid(amount, currency, captured, refunded);
      // A void after capture gives the money back in full: for the books, a full refund.
      case "voided" -> captured > 0 ? Snapshot.paid(amount, currency, captured, captured) : Snapshot.of(GatewayStatus.VOIDED, amount, currency);
      case "failed" -> noMoney(GatewayStatus.FAILED, amount, currency, captured)
          .withFailure("declined", source.path("message").asString(null));
      default -> throw new IllegalArgumentException("unknown status");
    };
    String number = source.path("number").asString("");
    return number.length() < 4 ? snapshot : snapshot.withCard(source.path("company").asString(null), number.substring(number.length() - 4));
  }

  /** A record with a status that means "no money was taken". If it shows money all the same, it cannot be true. */
  private static Snapshot noMoney(GatewayStatus status, long amount, String currency, long captured) {
    if (captured != 0) {
      throw new IllegalArgumentException("money captured in a status without money");
    }
    return Snapshot.of(status, amount, currency);
  }

  /** A whole number of halalas. Missing counts as zero; a fraction or text is refused. */
  private static long whole(JsonNode json, String field) {
    JsonNode value = json.path(field);
    if (value.isMissingNode() || value.isNull()) {
      return 0;
    }
    if (!value.isIntegralNumber()) {
      throw new IllegalArgumentException(field + " is not a whole number");
    }
    return value.asLong();
  }
}
