package dev.mizan.app.application;

import dev.mizan.app.application.model.DiscrepancyRecord;
import dev.mizan.app.application.model.LineRecord;
import dev.mizan.app.application.model.LineStatus;
import dev.mizan.app.application.model.PaymentRecord;
import dev.mizan.app.application.port.Discrepancies;
import dev.mizan.app.application.port.Ledger;
import dev.mizan.app.application.port.Payments;
import dev.mizan.app.application.port.Settlements;
import dev.mizan.app.application.port.Transactions;
import dev.mizan.core.ledger.Entries;
import dev.mizan.core.settlement.DiscrepancyType;
import dev.mizan.core.settlement.MatchOutcome;
import dev.mizan.core.settlement.Matcher;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Finance closes a discrepancy: by matching its line again, or by accepting the difference. */
@Service
public class ResolveDiscrepancy {

  /** The line discrepancies that a new look at the gateway can explain. */
  private static final Set<String> RETRYABLE = Set.of(
      DiscrepancyType.UNKNOWN_PAYMENT.name(), DiscrepancyType.NOT_CAPTURED_HERE.name(),
      DiscrepancyType.AMOUNT_MISMATCH.name(), DiscrepancyType.UNKNOWN_REFUND.name());

  private final Discrepancies discrepancies;
  private final Settlements settlements;
  private final Payments payments;
  private final Ledger ledger;
  private final SettlementWriter writer;
  private final SyncPayment sync;
  private final Transactions transactions;
  private final InstantSource clock;

  public ResolveDiscrepancy(
      Discrepancies discrepancies, Settlements settlements, Payments payments, Ledger ledger, SettlementWriter writer, SyncPayment sync,
      Transactions transactions, InstantSource clock) {
    this.discrepancies = discrepancies;
    this.settlements = settlements;
    this.payments = payments;
    this.ledger = ledger;
    this.writer = writer;
    this.sync = sync;
    this.transactions = transactions;
    this.clock = clock;
  }

  /**
   * Syncs the line's payment and matches the line again. If it fits now, the money leaves suspense for the payment
   * (a rematch entry) and the discrepancy is resolved. If not, nothing changes.
   */
  public DiscrepancyRecord retry(UUID id) {
    DiscrepancyRecord found = discrepancies.find(id).orElseThrow(() -> Problem.notFound("discrepancy"));
    requireOpen(found);
    if (found.lineId() == null || !RETRYABLE.contains(found.type())) {
      throw new Problem(Problem.Kind.CONFLICT, "not_retryable", "Only an unmatched settlement line can be matched again.");
    }
    LineRecord line = settlements.line(found.lineId()).orElseThrow();
    // The look at the gateway comes first, with no transaction open (rule R5).
    payments.findByGatewayId(line.gateway(), line.line().gatewayPaymentId()).ifPresent(payment -> sync.handle(payment.id()));

    return transactions.run(() -> {
      Instant now = clock.instant();
      DiscrepancyRecord open = discrepancies.lock(id).orElseThrow();
      requireOpen(open);
      LineRecord locked = settlements.lockLine(open.lineId()).orElseThrow();
      Optional<PaymentRecord> payment = payments.findByGatewayId(locked.gateway(), locked.line().gatewayPaymentId())
          .flatMap(p -> payments.lock(p.id()));
      MatchOutcome outcome = Matcher.match(locked.line(), payment.map(PaymentRecord::forSettlement).orElse(null), false, true);
      if (!(outcome instanceof MatchOutcome.Matched)) {
        throw new Problem(Problem.Kind.CONFLICT, "still_unmatched", "The line still does not fit what Mizan recorded. Nothing was changed.");
      }
      discrepancies.resolve(id, "REMATCHED", null, now); // before the fee check, which may open one for this line
      writer.matched(locked, payment.orElseThrow(), Entries.rematch(locked.id(), locked.line().gross(), locked.line().fee()), now);
      return discrepancies.find(id).orElseThrow();
    });
  }

  /** Finance accepts the difference. Money held in suspense for it moves to adjustments. */
  public DiscrepancyRecord accept(UUID id, String note) {
    if (note == null || note.isBlank() || note.length() > 500) {
      throw Problem.invalid("note_required", "Accepting a difference needs a note of 1 to 500 characters that says why.");
    }
    return transactions.run(() -> {
      Instant now = clock.instant();
      DiscrepancyRecord open = discrepancies.lock(id).orElseThrow(() -> Problem.notFound("discrepancy"));
      requireOpen(open);
      Entries.accept(id, open.suspenseAmount()).ifPresent(entry -> {
        if (!ledger.post(entry, open.paymentId(), now)) {
          throw new IllegalStateException("The ledger already has the entry " + entry.key() + ".");
        }
      });
      if (open.lineId() != null && settlements.lockLine(open.lineId()).orElseThrow().status() == LineStatus.SUSPENSE) {
        settlements.accepted(open.lineId());
      }
      discrepancies.resolve(id, "ACCEPTED", note.strip(), now);
      return discrepancies.find(id).orElseThrow();
    });
  }

  private static void requireOpen(DiscrepancyRecord discrepancy) {
    if (!discrepancy.open()) {
      throw new Problem(Problem.Kind.CONFLICT, "already_resolved", "This discrepancy was resolved before.");
    }
  }
}
