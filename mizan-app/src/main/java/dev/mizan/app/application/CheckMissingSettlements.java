package dev.mizan.app.application;

import dev.mizan.app.application.model.PaymentRecord;
import dev.mizan.app.application.port.Discrepancies;
import dev.mizan.app.application.port.Payments;
import dev.mizan.app.application.port.Transactions;
import dev.mizan.core.settlement.DiscrepancyType;
import dev.mizan.core.settlement.MissingSettlement;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The daily check: a capture that no file paid out, or a refund that no file took back, after the gateway's usual
 * time. Each such payment gets one open discrepancy, which closes by itself when a later line settles the rest.
 * See docs/lld/reconciliation.md, "Step 3".
 */
@Service
public class CheckMissingSettlements {

  private static final String MISSING = DiscrepancyType.MISSING_FROM_SETTLEMENT.name();

  private final Payments payments;
  private final Discrepancies discrepancies;
  private final SettlementWriter writer;
  private final Transactions transactions;
  private final Settings settings;
  private final InstantSource clock;

  public CheckMissingSettlements(
      Payments payments, Discrepancies discrepancies, SettlementWriter writer, Transactions transactions, Settings settings,
      InstantSource clock) {
    this.payments = payments;
    this.discrepancies = discrepancies;
    this.writer = writer;
    this.transactions = transactions;
    this.settings = settings;
    this.clock = clock;
  }

  /**
   * Opens the discrepancies. It closes none: only a matched line lowers what is missing, and matching closes the
   * discrepancy itself, under the payment's lock ({@link SettlementWriter#matched}).
   *
   * @return how many discrepancies it opened
   */
  public int handle() {
    Instant start = clock.instant();
    int opened = 0;
    for (PaymentRecord candidate : payments.unsettledSince(start.minus(settings.missingAfter()))) {
      if (transactions.run(() -> openIfMissing(candidate.id()))) {
        opened++;
      }
    }
    return opened;
  }

  // One short transaction per payment, under the payment's lock: a line matched meanwhile is seen, and the
  // workers are not held up by a long check.
  private boolean openIfMissing(UUID paymentId) {
    Instant now = clock.instant();
    PaymentRecord payment = payments.lock(paymentId).orElseThrow();
    OptionalLong missing = missing(payment, now);
    if (missing.isEmpty()) {
      return false; // settled since the list was read
    }
    Optional<UUID> id = discrepancies.open(MISSING, payment.id(), missing.getAsLong(), 0L, now);
    id.ifPresent(opened -> writer.told(opened, DiscrepancyType.MISSING_FROM_SETTLEMENT, payment, now));
    return id.isPresent();
  }

  private OptionalLong missing(PaymentRecord payment, Instant now) {
    return MissingSettlement.missing(
        payment.forSettlement(), payment.payment().capturedAt(), payment.payment().lastRefundAt(), settings.missingAfter(), now);
  }
}
