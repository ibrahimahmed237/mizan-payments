package dev.mizan.app.application.view;

import dev.mizan.app.application.model.OperationRecord;
import dev.mizan.app.application.model.PaymentRecord;
import dev.mizan.core.payment.OperationType;
import dev.mizan.core.payment.Payment;
import dev.mizan.core.payment.PaymentStatus;
import java.time.Instant;
import java.util.UUID;

/** A payment as the API returns it and as events carry it. */
public record PaymentView(
    UUID id,
    String status,
    long amount,
    String currency,
    String reference,
    String method,
    String capture,
    long capturedAmount,
    long refundedAmount,
    NextAction nextAction,
    String pendingReason,
    Failure failure,
    Card card,
    boolean late,
    String description,
    int version,
    Instant createdAt,
    Instant updatedAt) {

  /** The customer must go to this address. */
  public record NextAction(String type, String url) {}

  public record Failure(String code, String message) {}

  /** Card display data, from the gateway. */
  public record Card(String scheme, String last4) {}

  /** The customer has something left to do. */
  public static final String CUSTOMER_ACTION = "customer_action";
  /** The gateway's answer to the create call is not known yet. */
  public static final String GATEWAY_UNKNOWN = "gateway_unknown";

  /**
   * @param inFlight the payment's operation in flight, or null
   */
  public static PaymentView of(PaymentRecord record, OperationRecord inFlight) {
    Payment p = record.payment();
    boolean pending = p.status() == PaymentStatus.PENDING;
    boolean unknown = pending && inFlight != null && inFlight.type() == OperationType.CREATE;
    boolean action = pending && !unknown && p.actionUrl() != null;
    return new PaymentView(
        record.id(),
        p.status().name(),
        p.amount(),
        p.currency(),
        record.reference(),
        record.method().name(),
        p.captureMode().name(),
        p.captured(),
        p.refunded(),
        action ? new NextAction("redirect", p.actionUrl()) : null,
        unknown ? GATEWAY_UNKNOWN : action ? CUSTOMER_ACTION : null,
        p.failureCode() == null ? null : new Failure(p.failureCode(), p.failureMessage()),
        p.scheme() == null ? null : new Card(p.scheme(), p.last4()),
        p.late(),
        record.description(),
        record.version(),
        record.createdAt(),
        record.updatedAt());
  }
}
