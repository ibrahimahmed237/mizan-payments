package dev.mizan.app.application;

import dev.mizan.app.application.model.OperationRecord;
import dev.mizan.app.application.model.Origin;
import dev.mizan.app.application.model.PaymentRecord;
import dev.mizan.app.application.port.Discrepancies;
import dev.mizan.app.application.port.Ledger;
import dev.mizan.app.application.port.Operations;
import dev.mizan.app.application.port.Outbox;
import dev.mizan.app.application.port.Payments;
import dev.mizan.app.application.view.EventData;
import dev.mizan.app.application.view.OperationView;
import dev.mizan.app.application.view.PaymentView;
import dev.mizan.core.ledger.Entries;
import dev.mizan.core.ledger.Entry;
import dev.mizan.core.operation.CommandRules;
import dev.mizan.core.payment.OperationType;
import dev.mizan.core.payment.Payment;
import dev.mizan.core.payment.PaymentStatus;
import dev.mizan.core.schedule.SyncSchedule;
import dev.mizan.core.sync.Decision;
import dev.mizan.core.sync.Effect;
import dev.mizan.core.sync.Effect.Anomaly;
import dev.mizan.core.sync.Effect.CancelSiblings;
import dev.mizan.core.sync.Effect.Captured;
import dev.mizan.core.sync.Effect.ExternalRefund;
import dev.mizan.core.sync.Effect.FollowUpRequested;
import dev.mizan.core.sync.Effect.Notify;
import dev.mizan.core.sync.Effect.OperationFinished;
import dev.mizan.core.sync.Effect.Refunded;
import dev.mizan.core.sync.EventType;
import dev.mizan.core.sync.Snapshot;
import dev.mizan.core.sync.Sync;
import dev.mizan.core.sync.SyncContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Writes a decision of the rules to the database: the payment, the ledger, the operations, the events for the store
 * and the discrepancies, all in the caller's transaction (rule R6). Every change to a payment goes through here.
 *
 * <p>The caller holds the payment's lock.
 */
@Service
public class PaymentWriter {

  /**
   * A payment after a save.
   *
   * @param inFlight the operation still in flight after the save, or null
   */
  public record Saved(PaymentRecord payment, OperationRecord inFlight) {}

  /**
   * One event to write, once the payment's new state is known.
   *
   * @param operation the operation it is about, or null
   * @param discrepancy the id of the discrepancy it is about, or null
   * @param discrepancyType the type of that discrepancy, or null
   */
  private record Told(EventType type, OperationRecord operation, UUID discrepancy, String discrepancyType) {

    Told(EventType type, OperationRecord operation) {
      this(type, operation, null, null);
    }
  }

  private final Payments payments;
  private final Operations operations;
  private final Ledger ledger;
  private final Outbox outbox;
  private final Discrepancies discrepancies;
  private final Settings settings;

  public PaymentWriter(
      Payments payments, Operations operations, Ledger ledger, Outbox outbox, Discrepancies discrepancies, Settings settings) {
    this.payments = payments;
    this.operations = operations;
    this.ledger = ledger;
    this.outbox = outbox;
    this.discrepancies = discrepancies;
    this.settings = settings;
  }

  /** Applies the gateway's record of a payment: asks the rules, then writes their decision. */
  public Saved sync(PaymentRecord locked, OperationRecord inFlight, Snapshot snapshot, Instant now) {
    // Sync reads this only for a late capture, which comes to a payment that had ended and so holds no money itself.
    boolean otherMoney = payments.ofOrder(locked.reference()).stream().anyMatch(other -> CommandRules.hasMoney(other.payment()));
    var context = new SyncContext(settings.lateCapture(), settings.holdWindow(), otherMoney);
    Decision decision = Sync.decide(locked.payment(), inFlight == null ? null : inFlight.forRules(), snapshot, context, now);
    return apply(locked, inFlight, decision, snapshot, now);
  }

  /**
   * Writes a decision.
   *
   * @param before the payment as it was read under the lock
   * @param inFlight the payment's operation in flight, or null
   * @param seen the gateway's record that led to the decision; null when a rule decided without one
   */
  public Saved apply(PaymentRecord before, OperationRecord inFlight, Decision decision, Snapshot seen, Instant now) {
    Payment after = decision.payment();
    OperationRecord stillInFlight = inFlight;
    List<Told> told = new ArrayList<>();

    for (Effect effect : decision.effects()) {
      switch (effect) {
        case Captured captured -> post(Entries.capture(before.id(), captured.amount(), after.captured()), before.id(), now);
        case Refunded refunded -> post(Entries.refund(before.id(), refunded.amount(), after.refunded()), before.id(), now);
        case OperationFinished finished -> {
          if (stillInFlight == null) {
            throw new IllegalStateException("A decision closed an operation, but payment " + before.id() + " has none in flight.");
          }
          OperationRecord closed = stillInFlight.finished(finished.succeeded(), finished.code(), now);
          operations.finish(closed);
          EventType event = EventType.forOperation(closed.type(), finished.succeeded());
          if (event != null) {
            told.add(new Told(event, closed));
          }
          stillInFlight = null;
        }
        case ExternalRefund external -> {
          var refund = OperationRecord.done(UUID.randomUUID(), before.id(), OperationType.REFUND, Origin.GATEWAY, external.amount(), null, now);
          operations.insert(refund);
          told.add(new Told(EventType.REFUND_SUCCEEDED, refund));
        }
        case CancelSiblings siblings -> cancelSiblings(before, now);
        case FollowUpRequested followUp -> {
          // Kept in the payment itself. The sync worker starts it.
        }
        case Notify notify -> told.add(new Told(notify.event(), null));
        case Anomaly anomaly -> {
          Long actual = seen == null ? null : seen.amount();
          discrepancies.open(anomaly.type().name(), before.id(), before.payment().amount(), actual, now)
              .ifPresent(opened -> told.add(new Told(EventType.DISCREPANCY_OPENED, null, opened, anomaly.type().name())));
        }
      }
    }

    boolean changed = !after.equals(before.payment()) || !decision.effects().isEmpty();
    boolean synced = seen != null;
    Instant next = keepDue(before, now, SyncSchedule.next(
        after, stillInFlight == null ? null : stillInFlight.forRules(), before.createdAt(), settings.quietTime(), now));
    PaymentRecord saved = before.withPayment(after).saved(
        next,
        synced ? now : before.lastSyncedAt(),
        synced ? 0 : before.syncFailures(),
        changed ? before.version() + 1 : before.version(),
        changed ? now : before.updatedAt());
    payments.update(saved);

    PaymentView view = PaymentView.of(saved, stillInFlight);
    for (Told event : told) {
      Map<String, Object> data = event.discrepancy() != null
          ? EventData.discrepancy(event.discrepancy(), event.discrepancyType(), view)
          : EventData.of(view, event.operation() == null ? null : OperationView.of(event.operation()));
      outbox.add(event.type(), saved.id(), data, now);
    }
    return new Saved(saved, stillInFlight);
  }

  /**
   * A payment that someone asked to be looked at stays due. The request, a webhook for example, may have come while
   * this look was already on its way to the gateway, so the record in hand may be older than the news. Only a
   * worker that takes the payment for a new look takes the request down, and it does so before its fetch.
   */
  private static Instant keepDue(PaymentRecord before, Instant now, Instant next) {
    return before.syncRequested() ? now : next;
  }

  /** A fetch failed: count it, and look again later. Changes nothing else. */
  public PaymentRecord fetchFailed(PaymentRecord locked, Instant now) {
    int failures = locked.syncFailures() + 1;
    PaymentRecord saved = locked.saved(
        keepDue(locked, now, SyncSchedule.afterFailedFetch(failures, now)), locked.lastSyncedAt(), failures, locked.version(),
        locked.updatedAt());
    payments.update(saved);
    return saved;
  }

  private void post(Entry entry, UUID paymentId, Instant now) {
    if (!ledger.post(entry, paymentId, now)) {
      // The totals only grow under the payment's lock, so a fact cannot come twice. If it does, stop: a person must look.
      throw new IllegalStateException("The ledger already has the entry " + entry.key() + ".");
    }
  }

  /** This payment holds or has the order's money: the order's other unfinished payments end here. */
  private void cancelSiblings(PaymentRecord winner, Instant now) {
    for (PaymentRecord listed : payments.ofOrder(winner.reference())) {
      if (!listed.id().equals(winner.id()) && listed.payment().status() == PaymentStatus.PENDING) {
        // Its row is locked too, like any payment that changes. The order's lock is held already, so its state is
        // as listed; the row lock adds a fresh "look requested" flag, and makes a webhook for it wait for this save.
        PaymentRecord sibling = payments.lock(listed.id()).orElseThrow();
        // Its operation in flight, if any, stays in flight: its result is still worth knowing.
        apply(sibling, operations.inFlight(sibling.id()).orElse(null), CommandRules.cancelHere(sibling.payment()), null, now);
      }
    }
  }
}
