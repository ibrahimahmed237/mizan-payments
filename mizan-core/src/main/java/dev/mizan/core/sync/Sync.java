package dev.mizan.core.sync;

import dev.mizan.core.payment.CaptureMode;
import dev.mizan.core.payment.FollowUp;
import dev.mizan.core.payment.GatewayStatus;
import dev.mizan.core.payment.Operation;
import dev.mizan.core.payment.OperationType;
import dev.mizan.core.payment.Payment;
import dev.mizan.core.payment.PaymentStatus;
import dev.mizan.core.sync.Effect.Anomaly;
import dev.mizan.core.sync.Effect.CancelSiblings;
import dev.mizan.core.sync.Effect.Captured;
import dev.mizan.core.sync.Effect.ExternalRefund;
import dev.mizan.core.sync.Effect.FollowUpRequested;
import dev.mizan.core.sync.Effect.Notify;
import dev.mizan.core.sync.Effect.OperationFinished;
import dev.mizan.core.sync.Effect.Refunded;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Copies the gateway's record of a payment into Mizan. This is the only code that changes a payment's money, and
 * every path ends here: an operation's answer, a webhook, the customer's return, a timer, reconciliation.
 *
 * <p>It works on totals, not on events. A total only grows and a status only steps forward, so the same record
 * twice, or an old record after a new one, changes nothing. See docs/lld/sync.md.
 */
public final class Sync {

  private Sync() {}

  /**
   * @param payment the payment as Mizan has it
   * @param inFlight the operation in flight, or null
   * @param snapshot the gateway's record
   */
  public static Decision decide(Payment payment, Operation inFlight, Snapshot snapshot, SyncContext context, Instant now) {
    if (snapshot.status() == GatewayStatus.NOT_FOUND) {
      // Matters only for a create in flight, which the operation rules handle.
      return Decision.unchanged(payment);
    }

    // Step 1: a record with another amount is not this payment's money, whatever its status says.
    if (snapshot.amount() != payment.amount() || !snapshot.currency().equals(payment.currency())) {
      return new Decision(payment, List.of(new Anomaly(AnomalyType.AMOUNT_CONFLICT)));
    }

    var run = new Run(payment, inFlight, snapshot, context, now);
    run.moneyTaken();
    run.moneyGivenBack();
    run.statusWithoutMoney();
    run.closeOperation();
    run.remember();
    return new Decision(run.payment, run.effects);
  }

  /** One pass over the steps. Holds the payment as it changes and the effects so far. */
  private static final class Run {
    private final Payment before;
    private final Operation inFlight;
    private final Snapshot snapshot;
    private final SyncContext context;
    private final Instant now;
    private final List<Effect> effects = new ArrayList<>();
    private Payment payment;
    private boolean operationClosed;

    Run(Payment before, Operation inFlight, Snapshot snapshot, SyncContext context, Instant now) {
      this.before = before;
      this.payment = before;
      this.inFlight = inFlight;
      this.snapshot = snapshot;
      this.context = context;
      this.now = now;
    }

    /** Step 2. */
    void moneyTaken() {
      long delta = snapshot.captured() - before.captured();
      if (delta <= 0) {
        return;
      }
      boolean late = before.status().isDead();
      payment = payment.with(p -> {
        p.status = PaymentStatus.CAPTURED;
        p.captured = snapshot.captured();
        p.capturedAt = before.capturedAt() == null ? now : before.capturedAt();
        p.late = before.late() || late;
        p.actionUrl = null;
        p.pendingFollowUp = null; // nothing is left to capture or void
      });
      effects.add(new Captured(delta));
      effects.add(new Notify(EventType.PAYMENT_CAPTURED));
      if (inFlightIs(OperationType.CREATE) || inFlightIs(OperationType.CAPTURE)) {
        close(true, null);
      }
      if (late && (context.lateCapture() == LateCapturePolicy.REFUND || context.orderHasOtherMoney())) {
        request(FollowUp.AUTO_REFUND);
      } else if (before.status() != PaymentStatus.CAPTURED) {
        effects.add(new CancelSiblings());
      }
    }

    /** Step 3. */
    void moneyGivenBack() {
      long delta = snapshot.refunded() - before.refunded();
      if (delta <= 0) {
        return;
      }
      payment = payment.with(p -> {
        p.refunded = snapshot.refunded();
        p.lastRefundAt = now;
      });
      effects.add(new Refunded(delta));
      long external = delta;
      if (inFlightIs(OperationType.REFUND) && delta >= inFlight.amount()) {
        close(true, null);
        external = delta - inFlight.amount();
      }
      if (external > 0) {
        effects.add(new ExternalRefund(external));
      }
    }

    /** Step 4. */
    void statusWithoutMoney() {
      if (snapshot.status() == GatewayStatus.PAID) {
        return; // money was handled in the steps above
      }
      GatewayStatus last = before.gatewayStatus();
      if (last != null && snapshot.status().step() < last.step()) {
        return; // an old record. A captured payment's last status is PAID, so every status is old for it.
      }
      // A status on the same step as the last one changes no state below: the first end wins.
      switch (before.status()) {
        case PENDING -> fromPending();
        case AUTHORIZED -> fromAuthorized();
        case FAILED, CANCELED, EXPIRED -> {
          if (snapshot.status() == GatewayStatus.AUTHORIZED) {
            request(FollowUp.AUTO_VOID); // a late hold
          }
        }
        case CAPTURED -> {
          // A captured payment has money and left this step at its first line.
        }
      }
    }

    private void fromPending() {
      switch (snapshot.status()) {
        case INITIATED -> payment = payment.with(p -> p.actionUrl = snapshot.actionUrl());
        case AUTHORIZED -> {
          payment = payment.with(p -> {
            p.status = PaymentStatus.AUTHORIZED;
            p.holdEndsAt = now.plus(context.holdWindow());
            p.actionUrl = null;
          });
          effects.add(new Notify(EventType.PAYMENT_AUTHORIZED));
          effects.add(new CancelSiblings());
          if (before.captureMode() == CaptureMode.AUTO && !before.gateway().capturesByItself()) {
            request(FollowUp.AUTO_CAPTURE);
          }
        }
        case FAILED -> end(PaymentStatus.FAILED, EventType.PAYMENT_FAILED);
        case VOIDED -> end(PaymentStatus.CANCELED, EventType.PAYMENT_CANCELED);
        case EXPIRED -> end(PaymentStatus.EXPIRED, EventType.PAYMENT_EXPIRED);
        case NOT_FOUND, PAID -> {
          // Handled before this step.
        }
      }
    }

    private void fromAuthorized() {
      switch (snapshot.status()) {
        case FAILED -> end(PaymentStatus.FAILED, EventType.PAYMENT_FAILED); // the gateway withdrew the hold
        case VOIDED -> end(PaymentStatus.CANCELED, EventType.PAYMENT_CANCELED);
        case EXPIRED -> end(PaymentStatus.EXPIRED, EventType.PAYMENT_EXPIRED);
        case NOT_FOUND, INITIATED, AUTHORIZED, PAID -> {
          // Old records and records with money were handled before this step.
        }
      }
    }

    private void end(PaymentStatus status, EventType event) {
      payment = payment.with(p -> {
        p.status = status;
        p.actionUrl = null;
        p.pendingFollowUp = null; // nothing is left to capture or void
        if (status == PaymentStatus.FAILED) {
          p.failureCode = snapshot.failureCode() == null ? "gateway_declined" : snapshot.failureCode();
          p.failureMessage = snapshot.failureMessage();
        }
      });
      effects.add(new Notify(event));
    }

    /** Step 5. */
    void closeOperation() {
      if (inFlight == null || operationClosed) {
        return;
      }
      GatewayStatus status = snapshot.status();
      switch (inFlight.type()) {
        case CREATE -> close(true, null); // the gateway has the payment
        case CANCEL -> {
          if (status == GatewayStatus.VOIDED) {
            close(true, null);
          } else if (status == GatewayStatus.PAID) {
            close(false, "already_captured");
          }
        }
        case CAPTURE -> {
          if (status == GatewayStatus.VOIDED || status == GatewayStatus.EXPIRED || status == GatewayStatus.FAILED) {
            close(false, "hold_gone");
          }
        }
        case REFUND -> {
          // Closed in step 3, when the refunded total grew enough.
        }
      }
    }

    /** Step 6. */
    void remember() {
      GatewayStatus last = before.gatewayStatus();
      if (last == null || snapshot.status().step() > last.step()) {
        payment = payment.with(p -> p.gatewayStatus = snapshot.status());
      }
      if (snapshot.scheme() != null && before.scheme() == null) {
        payment = payment.with(p -> {
          p.scheme = snapshot.scheme();
          p.last4 = snapshot.last4();
        });
      }
    }

    private boolean inFlightIs(OperationType type) {
      return inFlight != null && !operationClosed && inFlight.type() == type;
    }

    private void close(boolean succeeded, String code) {
      operationClosed = true;
      effects.add(new OperationFinished(succeeded, code));
    }

    /** A follow-up is asked for once per payment and kind. */
    private void request(FollowUp kind) {
      if (payment.followUpsStarted().contains(kind) || payment.pendingFollowUp() == kind) {
        return;
      }
      payment = payment.with(p -> p.pendingFollowUp = kind);
      effects.add(new FollowUpRequested(kind));
    }
  }
}
