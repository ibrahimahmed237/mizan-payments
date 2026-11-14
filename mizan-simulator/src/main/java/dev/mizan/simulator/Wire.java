package dev.mizan.simulator;

import dev.mizan.core.money.Money;
import dev.mizan.core.payment.GatewayStatus;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Writes a payment in each gateway's own words. The shapes follow the public documents of real gateways. */
final class Wire {

  static final JsonMapper JSON = JsonMapper.builder().build();

  private Wire() {}

  /** The card gateway's payment object. Amounts are whole halalas. */
  static ObjectNode card(SimPayment p, String baseUrl, boolean amountChanged) {
    ObjectNode node = JSON.createObjectNode();
    node.put("id", p.id);
    node.put("status", cardStatus(p));
    node.put("amount", amountChanged ? p.amount + 100 : p.amount);
    node.put("currency", p.currency);
    node.put("captured", p.captured);
    node.put("refunded", p.voidedAfterCapture ? 0 : p.refunded);
    node.put("callback_url", p.callbackUrl);
    ObjectNode source = node.putObject("source");
    source.put("type", p.wallet ? "applepay" : "creditcard");
    source.put("company", p.scheme);
    source.put("number", "XXXX-XXXX-XXXX-" + p.last4);
    if (p.status == GatewayStatus.INITIATED && p.needsCustomer) {
      source.put("transaction_url", baseUrl + "/card/3ds/" + p.id);
    }
    if (p.failureMessage != null) {
      source.put("message", p.failureMessage);
    }
    ObjectNode metadata = node.putObject("metadata");
    metadata.put("reference", p.reference);
    metadata.put("mizan_payment_id", p.mizanPaymentId);
    return node;
  }

  /** The words of the card gateway: paid for a purchase, captured after a hold, refunded once anything was refunded. */
  static String cardStatus(SimPayment p) {
    return switch (p.status) {
      case INITIATED -> "initiated";
      case AUTHORIZED -> "authorized";
      case FAILED -> "failed";
      case VOIDED -> "voided";
      case PAID -> p.voidedAfterCapture ? "voided" : p.refunded > 0 ? "refunded" : p.manual ? "captured" : "paid";
      case EXPIRED, NOT_FOUND -> throw new IllegalStateException("The card gateway has no such status.");
    };
  }

  /** The pay-later provider's payment object. Amounts are riyals as text, such as "150.00". */
  static ObjectNode payLater(SimPayment p, boolean amountChanged) {
    ObjectNode node = JSON.createObjectNode();
    node.put("id", p.id);
    node.put("status", payLaterStatus(p));
    node.put("amount", Money.formatRiyals(amountChanged ? p.amount + 100 : p.amount));
    node.put("currency", p.currency);
    ArrayNode captures = node.putArray("captures");
    ArrayNode refunds = node.putArray("refunds");
    for (SimPayment.Transaction t : p.transactions) {
      ObjectNode item = (t.capture ? captures : refunds).addObject();
      item.put("id", t.id);
      item.put("amount", Money.formatRiyals(t.amount));
    }
    node.putObject("order").put("reference_id", p.mizanPaymentId);
    node.putObject("meta").put("order_id", p.reference);
    return node;
  }

  static String payLaterStatus(SimPayment p) {
    return switch (p.status) {
      case INITIATED -> "CREATED";
      case AUTHORIZED -> "AUTHORIZED";
      case PAID, VOIDED -> "CLOSED";
      case FAILED -> "REJECTED";
      case EXPIRED -> "EXPIRED";
      case NOT_FOUND -> throw new IllegalStateException("The provider has no such status.");
    };
  }
}
