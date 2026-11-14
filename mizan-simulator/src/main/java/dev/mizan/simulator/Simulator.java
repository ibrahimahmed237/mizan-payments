package dev.mizan.simulator;

import dev.mizan.core.fees.FeeRule;
import dev.mizan.core.payment.Gateway;
import dev.mizan.core.payment.GatewayStatus;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.node.ObjectNode;

/**
 * A fake card gateway and a fake pay-later provider in one object. It behaves as the public documents of real
 * gateways say, it can fail on purpose, and it keeps its own book of what really happened to each payment.
 *
 * <p>It is a test tool. It has no place on a public address.
 */
public final class Simulator {

  /** Keys, secrets, fees and how long a "timeout" fault keeps a caller waiting. */
  public record Config(
      String cardKey, String cardWebhookSecret, String payLaterKey, String payLaterWebhookSecret, FeeRule cardFee,
      FeeRule payLaterFee, Duration hang) {

    public static Config defaults() {
      return new Config(
          "sk_test_simulator", "card-webhook-secret", "pl_test_simulator", "pay-later-webhook-secret",
          new FeeRule(250, 100, 1500), new FeeRule(400, 0, 1500), Duration.ofSeconds(30));
    }

    public Config withHang(Duration newHang) {
      return new Config(cardKey, cardWebhookSecret, payLaterKey, payLaterWebhookSecret, cardFee, payLaterFee, newHang);
    }
  }

  private record Token(String scheme, String last4, boolean wallet) {}

  private record Task(Instant due, long order, Runnable work) {}

  private record PlannedFileFault(FileFault fault, String reference) {}

  /** Webhook retry waits of the card gateway this simulator follows: 1 min, 10 min, 30 min, 1 h, 2 h, then dropped. */
  private static final List<Duration> WEBHOOK_RETRIES =
      List.of(Duration.ofMinutes(1), Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofHours(2));

  private final Clock clock;
  private final Config config;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  private final Map<String, SimPayment> payments = new LinkedHashMap<>();
  private final Map<String, Token> tokens = new HashMap<>();
  private final Map<String, Deque<Fault>> callFaults = new HashMap<>();
  private final Map<String, Set<Fault>> stickyFaults = new HashMap<>();
  private final Map<String, Integer> fetchesDown = new HashMap<>();
  private final List<PlannedFileFault> fileFaults = new ArrayList<>();
  private final PriorityQueue<Task> tasks = new PriorityQueue<>(Comparator.comparing(Task::due).thenComparing(Task::order));
  private final Map<Gateway, URI> webhookTargets = new EnumMap<>(Gateway.class);
  private final Map<String, String> payouts = new LinkedHashMap<>();
  private String baseUrl = "http://simulator.invalid";
  private long sequence;
  private long bankTotal;

  public Simulator(Clock clock, Config config) {
    this.clock = clock;
    this.config = config;
  }

  public Config config() {
    return config;
  }

  public Clock clock() {
    return clock;
  }

  synchronized void baseUrl(String url) {
    this.baseUrl = url;
  }

  public synchronized String baseUrl() {
    return baseUrl;
  }

  /** Where webhooks of a gateway go. Without a target, none are sent. */
  public synchronized void webhookTarget(Gateway gateway, URI target) {
    webhookTargets.put(gateway, target);
  }

  // ---------------------------------------------------------------- faults

  /** The next call of this kind for the order fails in this way. Planned faults are used in the order given. */
  public synchronized void plan(String reference, Call call, Fault fault) {
    if (fault.sticky()) {
      throw new IllegalArgumentException(fault + " stays on a payment: use stick().");
    }
    callFaults.computeIfAbsent(reference + ":" + call, k -> new ArrayDeque<>()).add(fault);
  }

  /** Every payment of the order has this fault from now on. */
  public synchronized void stick(String reference, Fault fault) {
    if (!fault.sticky()) {
      throw new IllegalArgumentException(fault + " hits one call: use plan().");
    }
    stickyFaults.computeIfAbsent(reference, k -> EnumSet.noneOf(Fault.class)).add(fault);
  }

  /** The order's payments no longer have this fault: the gateway's trouble is over. */
  public synchronized void unstick(String reference, Fault fault) {
    Set<Fault> faults = stickyFaults.get(reference);
    if (faults != null) {
      faults.remove(fault);
    }
  }

  /** The next {@code times} fetches of the order's payments answer 503. */
  public synchronized void fetchDown(String reference, int times) {
    fetchesDown.put(reference, times);
  }

  /** The next settlement file is broken in this way. {@code reference} names the order when the fault needs one. */
  public synchronized void planFile(FileFault fault, String reference) {
    fileFaults.add(new PlannedFileFault(fault, reference));
  }

  synchronized Fault takeFault(String reference, Call call) {
    Deque<Fault> planned = callFaults.get(reference + ":" + call);
    return planned == null ? null : planned.poll();
  }

  synchronized boolean has(String reference, Fault fault) {
    return stickyFaults.getOrDefault(reference, Set.of()).contains(fault);
  }

  synchronized boolean fetchIsDown(String reference) {
    Integer left = fetchesDown.get(reference);
    if (left == null || left <= 0) {
      return false;
    }
    fetchesDown.put(reference, left - 1);
    return true;
  }

  // ---------------------------------------------------------------- the book

  /** Every payment the simulator has, in the order they were made. */
  public synchronized Collection<SimPayment> payments() {
    return List.copyOf(payments.values());
  }

  public synchronized SimPayment payment(String id) {
    return payments.get(id);
  }

  /** The payments of one order. */
  public synchronized List<SimPayment> paymentsOf(String reference) {
    return payments.values().stream().filter(p -> p.reference.equals(reference)).toList();
  }

  /** The sum of every payout so far: what really reached the bank. */
  public synchronized long bankTotal() {
    return bankTotal;
  }

  // ---------------------------------------------------------------- card gateway

  /** Stands in for the gateway's hosted page: turns a test card into a one-time token. */
  public synchronized String createToken(String cardNumber, String scheme, boolean wallet) {
    if (cardNumber == null || !cardNumber.matches("\\d{16}")) {
      throw new SimError(400, "invalid_request_error", "A card number has 16 digits.");
    }
    String token = (wallet ? "aptok_" : "tok_") + next();
    tokens.put(token, new Token(scheme == null ? "mada" : scheme, cardNumber.substring(12), wallet));
    return token;
  }

  /**
   * Creates a payment with the id the caller chose. The same id again returns the same payment and charges nothing.
   * The card's last four digits decide the result, as in real test modes: 1111 pays, 3333 needs the bank's code,
   * 0002 and 0069 are declined.
   */
  public synchronized SimPayment cardCreate(
      String givenId, long amount, String currency, String token, boolean manual, String callbackUrl, String reference,
      String mizanPaymentId) {
    SimPayment existing = payments.get(givenId);
    if (existing != null) {
      return existing;
    }
    if (givenId == null || !givenId.matches("[0-9a-f-]{36}")) {
      throw new SimError(400, "invalid_request_error", "given_id must be a UUID.");
    }
    if (amount < 100 || !"SAR".equals(currency)) {
      throw new SimError(400, "invalid_request_error", "The amount is at least 100 halalas, in SAR.");
    }
    Token card = tokens.remove(token);
    if (card == null) {
      throw new SimError(400, "invalid_request_error", "The token is not valid or was used before.");
    }
    boolean needsCustomer = !card.wallet && card.last4.equals("3333");
    var payment = new SimPayment(
        givenId, Gateway.CARD_GATEWAY, reference, mizanPaymentId, amount, currency, manual, needsCustomer, callbackUrl,
        card.scheme, card.last4, card.wallet);
    payments.put(givenId, payment);
    if (has(reference, Fault.WEBHOOK_FORGED)) {
      forgeWebhooks(payment);
    }
    switch (card.last4) {
      case "0002" -> fail(payment, "INSUFFICIENT FUNDS");
      case "0069" -> fail(payment, "EXPIRED CARD");
      case "3333" -> {
        if (card.wallet) {
          approve(payment);
        }
      }
      default -> approve(payment);
    }
    return payment;
  }

  public synchronized SimPayment cardCapture(String id, Long amount) {
    SimPayment p = require(id, Gateway.CARD_GATEWAY);
    if (p.status != GatewayStatus.AUTHORIZED) {
      throw new SimError(400, "invalid_request_error", "Only an authorized payment can be captured.");
    }
    if (has(p.reference, Fault.HOLD_RELEASED)) {
      throw new SimError(400, "authorization_expired", "The issuer released the hold.");
    }
    long capture = amount == null ? p.amount : amount;
    if (capture <= 0 || capture > p.amount) {
      throw new SimError(400, "invalid_request_error", "The capture amount is not valid.");
    }
    take(p, capture);
    emit(p, "payment_captured");
    return p;
  }

  public synchronized SimPayment cardVoid(String id) {
    SimPayment p = require(id, Gateway.CARD_GATEWAY);
    if (p.status == GatewayStatus.AUTHORIZED) {
      p.status = GatewayStatus.VOIDED;
    } else if (p.status == GatewayStatus.PAID && p.refunded == 0) {
      // A void shortly after capture: the money goes back in full.
      p.voidedAfterCapture = true;
      p.refunded = p.captured;
      p.transactions.add(new SimPayment.Transaction("txn_" + next(), false, p.captured, clock.instant()));
    } else {
      throw new SimError(400, "invalid_request_error", "This payment cannot be voided.");
    }
    emit(p, "payment_voided");
    return p;
  }

  public synchronized SimPayment cardRefund(String id, Long amount) {
    SimPayment p = require(id, Gateway.CARD_GATEWAY);
    giveBack(p, amount == null ? p.captured - p.refunded : amount);
    emit(p, "payment_refunded");
    return p;
  }

  // ---------------------------------------------------------------- pay-later provider

  /** Creates a checkout session. Nothing can be paid until the customer approves on the provider's page. */
  public synchronized SimPayment payLaterCheckout(long amount, String currency, String mizanPaymentId, String reference, String returnUrl) {
    if (amount < 100 || !"SAR".equals(currency)) {
      throw new SimError(400, "bad_request", "The amount is at least 1.00 SAR.");
    }
    var payment = new SimPayment(
        "pl_" + next(), Gateway.PAY_LATER, reference, mizanPaymentId, amount, currency, true, true, returnUrl, null, null, false);
    payments.put(payment.id, payment);
    return payment;
  }

  /** The provider captures the full amount only when asked in full; the payment then closes. */
  public synchronized SimPayment payLaterCapture(String id, long amount) {
    SimPayment p = require(id, Gateway.PAY_LATER);
    if (p.status != GatewayStatus.AUTHORIZED) {
      throw new SimError(400, "bad_request", "Only an authorized payment can be captured.");
    }
    if (has(p.reference, Fault.HOLD_RELEASED)) {
      throw new SimError(400, "authorization_expired", "The authorization is no longer valid.");
    }
    if (amount != p.amount) {
      throw new SimError(400, "bad_request", "The simulator captures the full amount only.");
    }
    take(p, amount);
    emit(p, "closed");
    return p;
  }

  /** Closes an authorized payment without capturing: the hold is released. */
  public synchronized SimPayment payLaterClose(String id) {
    SimPayment p = require(id, Gateway.PAY_LATER);
    if (p.status != GatewayStatus.AUTHORIZED) {
      throw new SimError(400, "bad_request", "Only an authorized payment can be closed.");
    }
    p.status = GatewayStatus.VOIDED;
    emit(p, "closed");
    return p;
  }

  /** A refund with a key: the same key again returns the payment and refunds nothing. */
  public synchronized SimPayment payLaterRefund(String id, long amount, String key) {
    SimPayment p = require(id, Gateway.PAY_LATER);
    if (key != null && p.refundsByKey.containsKey(key)) {
      return p;
    }
    giveBack(p, amount);
    if (key != null) {
      p.refundsByKey.put(key, amount);
    }
    return p;
  }

  // ---------------------------------------------------------------- the customer and the outside world

  /** The customer approves on the bank's or the provider's page. Returns where the browser is sent next. */
  public synchronized URI customerApproves(String id) {
    SimPayment p = payments.get(id);
    if (p == null) {
      throw new SimError(404, "record_not_found", "No such payment.");
    }
    if (p.status == GatewayStatus.INITIATED) {
      approve(p);
    }
    return returnAddress(p);
  }

  /** The customer's bank or the provider says no. */
  public synchronized URI customerDeclines(String id) {
    SimPayment p = payments.get(id);
    if (p == null) {
      throw new SimError(404, "record_not_found", "No such payment.");
    }
    if (p.status == GatewayStatus.INITIATED) {
      fail(p, p.gateway == Gateway.PAY_LATER ? "REJECTED" : "3-D SECURE FAILED");
    }
    return returnAddress(p);
  }

  /** The customer closes the provider's page: a pay-later session expires. A card payment just stays initiated. */
  public synchronized void customerLeaves(String id) {
    SimPayment p = payments.get(id);
    if (p != null && p.gateway == Gateway.PAY_LATER && p.status == GatewayStatus.INITIATED) {
      p.status = GatewayStatus.EXPIRED;
      emit(p, "expired");
    }
  }

  /** A refund made in the gateway's dashboard, not through Mizan. */
  public synchronized void externalRefund(String id, long amount) {
    SimPayment p = payments.get(id);
    if (p == null) {
      throw new SimError(404, "record_not_found", "No such payment.");
    }
    giveBack(p, amount);
    if (p.gateway == Gateway.CARD_GATEWAY) {
      emit(p, "payment_refunded");
    }
  }

  private URI returnAddress(SimPayment p) {
    String separator = p.callbackUrl != null && p.callbackUrl.contains("?") ? "&" : "?";
    // The gateway adds its own view of the result. It passes through the customer's browser: never trust it.
    String status = p.gateway == Gateway.PAY_LATER ? Wire.payLaterStatus(p) : Wire.cardStatus(p);
    return URI.create(p.callbackUrl + separator + "id=" + p.id + "&status=" + status);
  }

  // ---------------------------------------------------------------- money

  private void approve(SimPayment p) {
    if (p.manual) {
      p.status = GatewayStatus.AUTHORIZED;
      p.holds++;
      emit(p, p.gateway == Gateway.PAY_LATER ? "authorized" : "payment_authorized");
    } else {
      take(p, p.amount);
      emit(p, "payment_paid");
    }
  }

  private void fail(SimPayment p, String message) {
    p.status = GatewayStatus.FAILED;
    p.failureMessage = message;
    emit(p, p.gateway == Gateway.PAY_LATER ? "rejected" : "payment_failed");
  }

  private void take(SimPayment p, long amount) {
    p.status = GatewayStatus.PAID;
    p.captured += amount;
    p.charges++;
    p.transactions.add(new SimPayment.Transaction("txn_" + next(), true, amount, clock.instant()));
  }

  private void giveBack(SimPayment p, long amount) {
    // An unpaid payment has nothing captured, and a payment voided after capture has nothing left.
    if (amount <= 0 || amount > p.captured - p.refunded) {
      throw new SimError(400, "invalid_request_error", "The refund is more than what can be refunded.");
    }
    p.refunded += amount;
    p.transactions.add(new SimPayment.Transaction("txn_" + next(), false, amount, clock.instant()));
  }

  private SimPayment require(String id, Gateway gateway) {
    SimPayment p = payments.get(id);
    if (p == null || p.gateway != gateway) {
      throw new SimError(404, "record_not_found", "No such payment.");
    }
    return p;
  }

  private String next() {
    return Long.toString(++sequence, 36);
  }

  // ---------------------------------------------------------------- webhooks

  private void emit(SimPayment p, String event) {
    URI target = webhookTargets.get(p.gateway);
    if (target == null || has(p.reference, Fault.WEBHOOK_DROP)) {
      return;
    }
    int number = p.webhooksSent++;
    String body = webhookBody(p, event, true);
    Duration delay = Duration.ZERO;
    if (has(p.reference, Fault.WEBHOOK_LATE)) {
      delay = Duration.ofMinutes(5);
    } else if (has(p.reference, Fault.WEBHOOK_REORDER)) {
      delay = Duration.ofSeconds(Math.max(1, 30 - 10L * number)); // the first event waits longest
    }
    int copies = has(p.reference, Fault.WEBHOOK_DUP) ? 3 : 1;
    for (int i = 0; i < copies; i++) {
      schedule(delay, () -> deliver(p.gateway, target, body, 0));
    }
    if (has(p.reference, Fault.WEBHOOK_EARLY)) {
      runDueTasks(); // before the call that caused it is answered
    }
  }

  /** One webhook with a wrong secret, and one with the right secret that claims the payment is paid. */
  private void forgeWebhooks(SimPayment p) {
    URI target = webhookTargets.get(p.gateway);
    if (target == null) {
      return;
    }
    String wrongSecret = webhookBody(p, "payment_paid", false);
    String rightSecretFalseClaim = webhookBody(p, "payment_paid", true).replace("\"status\":\"initiated\"", "\"status\":\"paid\"");
    schedule(Duration.ZERO, () -> deliver(p.gateway, target, wrongSecret, WEBHOOK_RETRIES.size()));
    schedule(Duration.ZERO, () -> deliver(p.gateway, target, rightSecretFalseClaim, WEBHOOK_RETRIES.size()));
  }

  private String webhookBody(SimPayment p, String event, boolean rightSecret) {
    if (p.gateway == Gateway.PAY_LATER) {
      ObjectNode body = Wire.payLater(p, false);
      body.put("status", event); // the provider writes webhook statuses in lower case
      return body.toString();
    }
    ObjectNode body = Wire.JSON.createObjectNode();
    body.put("id", "evt_" + next());
    body.put("type", event);
    body.put("created_at", clock.instant().toString());
    body.put("secret_token", rightSecret ? config.cardWebhookSecret() : "not-the-secret");
    body.put("live", false);
    body.set("data", Wire.card(p, baseUrl, false));
    return body.toString();
  }

  /** Sends one webhook. Runs outside the simulator's lock. A failed delivery is tried again later, as documented. */
  private void deliver(Gateway gateway, URI target, String body, int attempt) {
    var request = HttpRequest.newBuilder(target)
        .timeout(Duration.ofSeconds(5))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    if (gateway == Gateway.PAY_LATER) {
      request.header("X-Signature", sign(config.payLaterWebhookSecret(), body));
    }
    boolean delivered;
    try {
      delivered = http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode() / 100 == 2;
    } catch (java.io.IOException e) {
      delivered = false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }
    if (!delivered && attempt < WEBHOOK_RETRIES.size()) {
      schedule(WEBHOOK_RETRIES.get(attempt), () -> deliver(gateway, target, body, attempt + 1));
    }
  }

  /** The pay-later provider's signature: hex HMAC-SHA256 of the body. */
  public static String sign(String secret, String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException(e);
    }
  }

  // ---------------------------------------------------------------- time

  synchronized void schedule(Duration delay, Runnable work) {
    tasks.add(new Task(clock.instant().plus(delay), ++sequence, work));
  }

  /**
   * Runs everything that is due at the simulator's clock: webhooks, and work that a fault put off. A background
   * thread calls this; a test with a clock it moves by hand calls it after each move.
   */
  public void runDueTasks() {
    while (true) {
      Task task;
      synchronized (this) {
        task = tasks.peek();
        if (task == null || task.due().isAfter(clock.instant())) {
          return;
        }
        tasks.poll();
      }
      task.work().run(); // outside the lock: a webhook is a network call
    }
  }

  // ---------------------------------------------------------------- settlement

  /**
   * Closes a payout: every capture and refund of the gateway that is not settled yet goes into one settlement file,
   * in Mizan's neutral format. Planned file faults break it. The same date again returns the same file.
   */
  public synchronized String closePayout(Gateway gateway, LocalDate date) {
    String payoutId = "po_" + (gateway == Gateway.CARD_GATEWAY ? "card" : "paylater") + "_" + date;
    String existing = payouts.get(payoutId);
    if (existing != null) {
      return existing;
    }
    FeeRule rule = gateway == Gateway.CARD_GATEWAY ? config.cardFee() : config.payLaterFee();
    List<String> lines = new ArrayList<>();
    long total = 0;
    for (SimPayment p : payments.values()) {
      if (p.gateway != gateway) {
        continue;
      }
      for (SimPayment.Transaction t : p.transactions) {
        if (t.payoutId != null) {
          continue;
        }
        t.payoutId = payoutId;
        if (t.capture && takeFileFault(FileFault.MISSING_LINE, p.reference)) {
          continue; // settled in the simulator's mind, never shown to Mizan
        }
        long gross = t.capture ? t.amount : -t.amount;
        if (t.capture && takeFileFault(FileFault.WRONG_AMOUNT, p.reference)) {
          gross += 100;
        }
        if (t.capture && takeFileFault(FileFault.SHORT_AMOUNT, p.reference)) {
          gross -= 100;
        }
        // The fee fits the amount on the line, so a wrong amount is one fault and not two.
        long fee = t.capture ? rule.feeFor(gross) : 0;
        if (t.capture && takeFileFault(FileFault.WRONG_FEE, p.reference)) {
          fee += 7;
        }
        total += gross - fee;
        lines.add(line(t.id, p.id, t.capture ? "capture" : "refund", gross, fee, t.at));
      }
    }
    for (PlannedFileFault planned : List.copyOf(fileFaults)) {
      SimPayment p = planned.reference() == null ? null
          : payments.values().stream().filter(x -> x.reference.equals(planned.reference()) && x.gateway == gateway).findFirst().orElse(null);
      switch (planned.fault()) {
        case EXTRA_LINE -> {
          long fee = rule.feeFor(5_000);
          total += 5_000 - fee;
          lines.add(line("txn_ghost_" + next(), "ghost_" + next(), "capture", 5_000, fee, clock.instant()));
          fileFaults.remove(planned);
        }
        case DUPLICATE_LINE -> {
          SimPayment.Transaction old = p == null ? null
              : p.transactions.stream().filter(t -> t.capture && t.payoutId != null && !t.payoutId.equals(payoutId)).findFirst().orElse(null);
          if (old != null) {
            long fee = rule.feeFor(old.amount);
            total += old.amount - fee;
            lines.add(line(old.id, p.id, "capture", old.amount, fee, old.at));
            fileFaults.remove(planned);
          }
        }
        case CHARGEBACK_LINE -> {
          if (p != null && p.captured > 0) {
            total -= p.captured;
            lines.add(line("txn_cb_" + next(), p.id, "chargeback", -p.captured, 0, clock.instant()));
            fileFaults.remove(planned);
          }
        }
        default -> {
          // The other faults were applied to their lines above, or are applied to the whole file below.
        }
      }
    }
    bankTotal += total;
    long stated = takeFileFault(FileFault.BAD_TOTAL, null) ? total + 1 : total;
    String gatewayName = gateway == Gateway.CARD_GATEWAY ? "card_gateway" : "pay_later";
    String text = "H," + payoutId + "," + gatewayName + ",SAR," + date + "," + stated + "," + lines.size() + "\n"
        + String.join("\n", lines) + (lines.isEmpty() ? "" : "\n");
    if (takeFileFault(FileFault.BROKEN_FILE, null)) {
      text = text.substring(0, Math.max(1, text.length() - 12));
    }
    payouts.put(payoutId, text);
    return text;
  }

  private static String line(String transactionId, String paymentId, String type, long gross, long fee, Instant at) {
    return "L," + transactionId + "," + paymentId + "," + type + "," + gross + "," + fee + "," + (gross - fee) + "," + at;
  }

  private boolean takeFileFault(FileFault fault, String reference) {
    for (PlannedFileFault planned : fileFaults) {
      if (planned.fault() == fault && (reference == null || reference.equals(planned.reference()))) {
        fileFaults.remove(planned);
        return true;
      }
    }
    return false;
  }
}
