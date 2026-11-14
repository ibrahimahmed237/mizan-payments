package dev.mizan.core.payment;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The part of a payment that the rules read and change. It cannot be built in a state that breaks the limits:
 * {@code 0 ≤ refunded ≤ captured ≤ amount}, and {@code CAPTURED} exactly when something was captured.
 *
 * @param gatewayStatus the gateway's last status that Mizan saw; null before the first record
 * @param late the money was taken after the store was told the payment was dead
 * @param pendingFollowUp an operation Mizan must start by itself when no operation is in flight; null when none
 * @param followUpsStarted the follow-ups that were started already; each kind happens once
 */
public record Payment(
    long amount,
    String currency,
    Gateway gateway,
    CaptureMode captureMode,
    PaymentStatus status,
    long captured,
    long refunded,
    GatewayStatus gatewayStatus,
    String actionUrl,
    String failureCode,
    String failureMessage,
    String scheme,
    String last4,
    boolean late,
    FollowUp pendingFollowUp,
    Set<FollowUp> followUpsStarted,
    Instant expiresAt,
    Instant holdEndsAt,
    Instant capturedAt,
    Instant lastRefundAt) {

  public Payment {
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(gateway, "gateway");
    Objects.requireNonNull(captureMode, "captureMode");
    Objects.requireNonNull(status, "status");
    followUpsStarted = followUpsStarted == null || followUpsStarted.isEmpty()
        ? Set.of()
        : Set.copyOf(EnumSet.copyOf(followUpsStarted));
    if (amount <= 0) {
      throw new IllegalArgumentException("A payment's amount is above zero.");
    }
    if (refunded < 0 || refunded > captured || captured > amount) {
      throw new IllegalArgumentException(
          "The limits 0 ≤ refunded ≤ captured ≤ amount are broken: " + refunded + ", " + captured + ", " + amount);
    }
    if ((status == PaymentStatus.CAPTURED) != (captured > 0)) {
      throw new IllegalArgumentException("A payment is CAPTURED exactly when something was captured.");
    }
  }

  /** A new payment: nothing known from the gateway yet. */
  public static Payment pending(long amount, String currency, Gateway gateway, CaptureMode captureMode, Instant expiresAt) {
    return new Payment(
        amount, currency, gateway, captureMode, PaymentStatus.PENDING, 0, 0, null, null, null, null, null, null,
        false, null, Set.of(), expiresAt, null, null, null);
  }

  /** What can still be refunded. */
  public long refundable() {
    return captured - refunded;
  }

  /** A copy with some values changed. The copy is checked like any payment. */
  public Payment with(Consumer<Draft> change) {
    var draft = new Draft(this);
    change.accept(draft);
    return draft.build();
  }

  /** The values of a payment, open for change, used only to make a changed copy. */
  public static final class Draft {
    public long amount;
    public String currency;
    public Gateway gateway;
    public CaptureMode captureMode;
    public PaymentStatus status;
    public long captured;
    public long refunded;
    public GatewayStatus gatewayStatus;
    public String actionUrl;
    public String failureCode;
    public String failureMessage;
    public String scheme;
    public String last4;
    public boolean late;
    public FollowUp pendingFollowUp;
    public Set<FollowUp> followUpsStarted;
    public Instant expiresAt;
    public Instant holdEndsAt;
    public Instant capturedAt;
    public Instant lastRefundAt;

    private Draft(Payment p) {
      amount = p.amount;
      currency = p.currency;
      gateway = p.gateway;
      captureMode = p.captureMode;
      status = p.status;
      captured = p.captured;
      refunded = p.refunded;
      gatewayStatus = p.gatewayStatus;
      actionUrl = p.actionUrl;
      failureCode = p.failureCode;
      failureMessage = p.failureMessage;
      scheme = p.scheme;
      last4 = p.last4;
      late = p.late;
      pendingFollowUp = p.pendingFollowUp;
      followUpsStarted = p.followUpsStarted;
      expiresAt = p.expiresAt;
      holdEndsAt = p.holdEndsAt;
      capturedAt = p.capturedAt;
      lastRefundAt = p.lastRefundAt;
    }

    private Payment build() {
      return new Payment(
          amount, currency, gateway, captureMode, status, captured, refunded, gatewayStatus, actionUrl, failureCode,
          failureMessage, scheme, last4, late, pendingFollowUp, followUpsStarted, expiresAt, holdEndsAt, capturedAt,
          lastRefundAt);
    }
  }
}
