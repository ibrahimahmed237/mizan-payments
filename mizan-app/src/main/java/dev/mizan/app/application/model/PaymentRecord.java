package dev.mizan.app.application.model;

import dev.mizan.core.payment.Method;
import dev.mizan.core.payment.Payment;
import java.time.Instant;
import java.util.UUID;

/**
 * A payment as it is stored: the part the rules own ({@link Payment}) and what the service keeps around it.
 *
 * @param reference the store's order
 * @param gatewayPaymentId the payment's id at the gateway; null until the gateway gave one
 * @param returnUrl where the customer is sent after paying
 * @param settledCaptured how much of the captured total the gateway has paid out
 * @param settledRefunded how much of the refunded total the gateway has taken back out of payouts
 * @param nextSyncAt when the sync worker looks next; null: never
 * @param syncRequested someone asked for a look, and no worker has taken the payment for one since
 * @param syncFailures fetches that failed in a row
 * @param version grows with every change; sent in events
 */
public record PaymentRecord(
    UUID id,
    String reference,
    Method method,
    String description,
    String returnUrl,
    String gatewayPaymentId,
    Payment payment,
    long settledCaptured,
    long settledRefunded,
    Instant nextSyncAt,
    boolean syncRequested,
    Instant lastSyncedAt,
    int syncFailures,
    int version,
    Instant createdAt,
    Instant updatedAt) {

  public PaymentRecord withPayment(Payment changed) {
    return new PaymentRecord(
        id, reference, method, description, returnUrl, gatewayPaymentId, changed, settledCaptured, settledRefunded,
        nextSyncAt, syncRequested, lastSyncedAt, syncFailures, version, createdAt, updatedAt);
  }

  public PaymentRecord withGatewayPaymentId(String gatewayId) {
    return new PaymentRecord(
        id, reference, method, description, returnUrl, gatewayId, payment, settledCaptured, settledRefunded,
        nextSyncAt, syncRequested, lastSyncedAt, syncFailures, version, createdAt, updatedAt);
  }

  /** The bookkeeping of one save. */
  public PaymentRecord saved(Instant nextSync, Instant lastSynced, int failures, int newVersion, Instant updated) {
    return new PaymentRecord(
        id, reference, method, description, returnUrl, gatewayPaymentId, payment, settledCaptured, settledRefunded,
        nextSync, syncRequested, lastSynced, failures, newVersion, createdAt, updated);
  }
}
