package dev.mizan.app.application;

import dev.mizan.app.application.model.LineRecord;
import dev.mizan.app.application.model.LineStatus;
import dev.mizan.app.application.model.PaymentRecord;
import dev.mizan.app.application.port.Discrepancies;
import dev.mizan.app.application.port.Ledger;
import dev.mizan.app.application.port.Operations;
import dev.mizan.app.application.port.Outbox;
import dev.mizan.app.application.port.Payments;
import dev.mizan.app.application.port.Settlements;
import dev.mizan.app.application.view.EventData;
import dev.mizan.app.application.view.PaymentView;
import dev.mizan.core.ledger.Entries;
import dev.mizan.core.ledger.Entry;
import dev.mizan.core.settlement.DiscrepancyType;
import dev.mizan.core.settlement.LineType;
import dev.mizan.core.settlement.Matcher;
import dev.mizan.core.settlement.MissingSettlement;
import dev.mizan.core.settlement.SettlementLine;
import dev.mizan.core.sync.EventType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Writes what matching decided about a settlement line: the settled totals, the ledger entry, the line's status, and
 * the discrepancies with their events, all in the caller's transaction (rule R6). See docs/lld/reconciliation.md.
 *
 * <p>The caller holds the line's lock and, when there is one, the payment's lock.
 */
@Service
public class SettlementWriter {

  private final Payments payments;
  private final Operations operations;
  private final Settlements settlements;
  private final Ledger ledger;
  private final Discrepancies discrepancies;
  private final Outbox outbox;
  private final Settings settings;

  public SettlementWriter(
      Payments payments, Operations operations, Settlements settlements, Ledger ledger, Discrepancies discrepancies, Outbox outbox,
      Settings settings) {
    this.payments = payments;
    this.operations = operations;
    this.settlements = settlements;
    this.ledger = ledger;
    this.discrepancies = discrepancies;
    this.outbox = outbox;
    this.settings = settings;
  }

  /**
   * The line fits what is still unsettled: it is settled.
   *
   * @param entry the ledger entry: a settle entry, or a rematch entry when the bank side was posted before
   */
  public void matched(LineRecord line, PaymentRecord payment, Optional<Entry> entry, Instant now) {
    SettlementLine fact = line.line();
    PaymentRecord settled = fact.type() == LineType.CAPTURE
        ? payment.withSettled(payment.settledCaptured() + fact.gross(), payment.settledRefunded())
        : payment.withSettled(payment.settledCaptured(), payment.settledRefunded() - fact.gross());
    payments.update(settled);
    entry.ifPresent(e -> post(e, payment.id(), now));
    settlements.handled(line.id(), LineStatus.MATCHED, payment.id(), now);

    // The money is explained. Its price may not be: the line keeps the real fee, and a person is told.
    Matcher.unexpectedFee(fact, settings.feeRule(line.gateway())).ifPresent(expected ->
        discrepancies.openForLine(DiscrepancyType.FEE_MISMATCH.name(), line.id(), payment.id(), expected, fact.fee(), 0, now)
            .ifPresent(opened -> told(opened, DiscrepancyType.FEE_MISMATCH, settled, now)));

    // A missing settlement that this line completes closes by itself.
    boolean stillMissing = MissingSettlement.missing(
        settled.forSettlement(), settled.payment().capturedAt(), settled.payment().lastRefundAt(), settings.missingAfter(), now).isPresent();
    if (!stillMissing) {
      discrepancies.lockOpenOf(payment.id(), DiscrepancyType.MISSING_FROM_SETTLEMENT.name())
          .ifPresent(open -> discrepancies.resolve(open.id(), "SETTLED_LATER", null, now));
    }
  }

  /**
   * The line does not fit. What reached the bank is held in suspense (rule R7), and a discrepancy says why.
   *
   * @param payment the line's payment; null when Mizan has none
   */
  public void mismatch(LineRecord line, PaymentRecord payment, DiscrepancyType type, Instant now) {
    SettlementLine fact = line.line();
    UUID paymentId = payment == null ? null : payment.id();
    Entries.suspense(line.id(), fact.net()).ifPresent(e -> post(e, paymentId, now));
    settlements.handled(line.id(), LineStatus.SUSPENSE, paymentId, now);

    // What Mizan expected, against what the line says.
    Long expected = switch (type) {
      case AMOUNT_MISMATCH, NOT_CAPTURED_HERE -> payment.forSettlement().unsettledCaptured();
      case UNKNOWN_REFUND -> payment.forSettlement().unsettledRefunded();
      default -> null;
    };
    long actual = fact.type() == LineType.REFUND ? -fact.gross() : fact.gross();
    discrepancies.openForLine(type.name(), line.id(), paymentId, expected, actual, fact.net(), now)
        .ifPresent(opened -> told(opened, type, payment, now));
  }

  /** The store is told of a discrepancy, with the payment as it is now when there is one. */
  public void told(UUID discrepancy, DiscrepancyType type, PaymentRecord payment, Instant now) {
    PaymentView view = payment == null ? null : PaymentView.of(payment, operations.inFlight(payment.id()).orElse(null));
    outbox.add(EventType.DISCREPANCY_OPENED, payment == null ? null : payment.id(), EventData.discrepancy(discrepancy, type.name(), view), now);
  }

  private void post(Entry entry, UUID paymentId, Instant now) {
    if (!ledger.post(entry, paymentId, now)) {
      // A line is handled once, under its lock. If its entry exists already, something else wrote it: stop.
      throw new IllegalStateException("The ledger already has the entry " + entry.key() + ".");
    }
  }
}
