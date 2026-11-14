package dev.mizan.app.adapter.in.web;

import dev.mizan.app.application.AdvancePayment;
import dev.mizan.app.application.CreatePayment;
import dev.mizan.app.application.PaymentQueries;
import dev.mizan.app.application.Problem;
import dev.mizan.app.application.RequestOperation;
import dev.mizan.app.application.port.Payments;
import dev.mizan.app.application.view.OperationView;
import dev.mizan.app.application.view.PaymentView;
import dev.mizan.core.payment.OperationType;
import dev.mizan.core.payment.PaymentStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The payments API. It translates between HTTP and the use cases, and holds no rule of its own. */
@RestController
@RequestMapping("/v1/payments")
class PaymentsController {

  /** The body of "create a payment". Every field may be missing here; the use case checks them. */
  record CreateBody(
      Long amount, String currency, String reference, String method, Source source, String capture, String returnUrl, String description) {

    record Source(String token) {}
  }

  /** The body of a capture, a cancel or a refund. It may be missing altogether. */
  record OperationBody(Long amount, String reason) {}

  private static final String REPLAY = "Idempotent-Replay";

  private final CreatePayment createPayment;
  private final RequestOperation requestOperation;
  private final AdvancePayment advancePayment;
  private final PaymentQueries queries;
  private final JsonMapper json;

  PaymentsController(
      CreatePayment createPayment, RequestOperation requestOperation, AdvancePayment advancePayment, PaymentQueries queries, JsonMapper json) {
    this.createPayment = createPayment;
    this.requestOperation = requestOperation;
    this.advancePayment = advancePayment;
    this.queries = queries;
    this.json = json;
  }

  @PostMapping
  ResponseEntity<PaymentView> create(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode raw,
      HttpServletRequest request) {
    String key = RequestHash.key(idempotencyKey);
    CreateBody body = json.treeToValue(object(raw), CreateBody.class);
    // A source without a token is still a source: the use case must see that one was sent.
    String token = body.source() == null ? null : body.source().token() == null ? "" : body.source().token();
    var result = createPayment.handle(new CreatePayment.Command(
        body.amount(), body.currency(), body.reference(), body.method(), token, body.capture(), body.returnUrl(),
        body.description(), key, RequestHash.of("POST", request.getRequestURI(), raw)));
    return result.replay()
        ? ResponseEntity.ok().header(REPLAY, "true").body(result.payment())
        : ResponseEntity.status(HttpStatus.CREATED).body(result.payment());
  }

  @GetMapping("/{id}")
  ObjectNode read(@PathVariable UUID id) {
    PaymentQueries.Details details = queries.details(id);
    ObjectNode payment = json.valueToTree(details.payment());
    payment.set("operations", json.valueToTree(details.operations()));
    return payment;
  }

  @GetMapping
  Map<String, Object> list(
      @RequestParam(required = false) String reference,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "20") int limit) {
    PaymentQueries.Page page = queries.list(reference, status(status), place(cursor), limit);
    Map<String, Object> answer = new LinkedHashMap<>();
    answer.put("data", page.payments());
    answer.put("next_cursor", page.next() == null ? null : cursor(page.next()));
    return answer;
  }

  @PostMapping("/{id}/capture")
  ResponseEntity<OperationView> capture(
      @PathVariable UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody(required = false) JsonNode raw,
      HttpServletRequest request) {
    return operation(id, OperationType.CAPTURE, idempotencyKey, raw, request);
  }

  @PostMapping("/{id}/cancel")
  ResponseEntity<OperationView> cancel(
      @PathVariable UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody(required = false) JsonNode raw,
      HttpServletRequest request) {
    return operation(id, OperationType.CANCEL, idempotencyKey, raw, request);
  }

  @PostMapping("/{id}/refunds")
  ResponseEntity<OperationView> refund(
      @PathVariable UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody(required = false) JsonNode raw,
      HttpServletRequest request) {
    return operation(id, OperationType.REFUND, idempotencyKey, raw, request);
  }

  @GetMapping("/{id}/refunds")
  Map<String, List<OperationView>> refunds(@PathVariable UUID id) {
    return Map.of("data", queries.refunds(id));
  }

  /** Does for this payment, now, what the sync worker does in its rounds. Returns the payment as it is after that. */
  @PostMapping("/{id}/sync")
  PaymentView sync(@PathVariable UUID id) {
    return queries.view(advancePayment.handle(id));
  }

  private ResponseEntity<OperationView> operation(UUID id, OperationType type, String idempotencyKey, JsonNode raw, HttpServletRequest request) {
    String key = RequestHash.key(idempotencyKey);
    OperationBody body = raw == null ? new OperationBody(null, null) : json.treeToValue(object(raw), OperationBody.class);
    if (body.reason() != null && body.reason().length() > 255) {
      throw Problem.invalid("reason_too_long", "The reason is at most 255 characters.");
    }
    var result = requestOperation.handle(new RequestOperation.Command(
        id, type, body.amount(), body.reason(), key, RequestHash.of("POST", request.getRequestURI(), raw)));
    if (result.replay()) {
      return ResponseEntity.ok().header(REPLAY, "true").body(result.operation());
    }
    // 202: the operation was sent, and its result is not known yet.
    boolean pending = "PENDING".equals(result.operation().status());
    return ResponseEntity.status(pending ? HttpStatus.ACCEPTED : HttpStatus.CREATED).body(result.operation());
  }

  private static JsonNode object(JsonNode raw) {
    if (!raw.isObject()) {
      throw new Problem(Problem.Kind.BAD_REQUEST, "malformed_body", "The body must be a JSON object.");
    }
    return raw;
  }

  private static PaymentStatus status(String value) {
    if (value == null) {
      return null;
    }
    try {
      return PaymentStatus.valueOf(value);
    } catch (IllegalArgumentException e) {
      throw Problem.invalid("invalid_status", "The status must be one of " + List.of(PaymentStatus.values()) + ".");
    }
  }

  /** A cursor is a place in the list, written so that a caller treats it as one opaque word. */
  private static String cursor(Payments.Place place) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString((place.createdAt() + "|" + place.id()).getBytes(StandardCharsets.UTF_8));
  }

  private static Payments.Place place(String cursor) {
    if (cursor == null) {
      return null;
    }
    try {
      String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|");
      return new Payments.Place(Instant.parse(parts[0]), UUID.fromString(parts[1]));
    } catch (RuntimeException e) {
      throw Problem.invalid("invalid_cursor", "The cursor is not one that this API gave.");
    }
  }
}
