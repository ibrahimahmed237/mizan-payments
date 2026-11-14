package dev.mizan.app.application;

import dev.mizan.app.application.model.DiscrepancyRecord;
import dev.mizan.app.application.model.PaymentRecord;
import dev.mizan.app.application.port.Discrepancies;
import dev.mizan.app.application.port.Payments;
import dev.mizan.app.application.port.Transactions;
import dev.mizan.core.settlement.DiscrepancyType;
import dev.mizan.core.settlement.MissingSettlement;
import java.time.Instant;
import java.time.InstantSource;
import java.util.OptionalLong;
import org.springframework.stereotype.Service;

/**
 * The daily check: a capture that no file paid out, or a refund that no file took back, after the gateway's usual
 * time. Each such payment gets one open discrepancy, which closes by itself when a later file settles the rest.
 * See docs/lld/reconciliation.md, "Step 3".
 */
@Service
public class CheckMissingSettlements {

  /** @param opened discrepancies opened by this check; @param closed discrepancies it found settled since */
  public record Result(int opened, int closed) {}

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

  public Result handle() {
    return transactions.run(() -> {
      Instant now = clock.instant();
      int opened = 0;
      for (PaymentRecord payment : payments.unsettledSince(now.minus(settings.missingAfter()))) {
        OptionalLong missing = missing(payment, now);
        if (missing.isPresent()) {
          var id = discrepancies.open(MISSING, payment.id(), missing.getAsLong(), 0L, now);
          if (id.isPresent()) {
            writer.told(id.get(), DiscrepancyType.MISSING_FROM_SETTLEMENT, payment, now);
            opened++;
          }
        }
      }
      int closed = 0;
      for (DiscrepancyRecord open : discrepancies.openOfType(MISSING)) {
        PaymentRecord payment = payments.find(open.paymentId()).orElseThrow();
        if (missing(payment, now).isEmpty()) {
          discrepancies.resolve(open.id(), "SETTLED_LATER", null, now);
          closed++;
        }
      }
      return new Result(opened, closed);
    });
  }

  private OptionalLong missing(PaymentRecord payment, Instant now) {
    return MissingSettlement.missing(
        payment.forSettlement(), payment.payment().capturedAt(), payment.payment().lastRefundAt(), settings.missingAfter(), now);
  }
}
