package dev.mizan.app.application;

import dev.mizan.app.application.model.LineRecord;
import dev.mizan.app.application.model.PaymentRecord;
import dev.mizan.app.application.port.Payments;
import dev.mizan.app.application.port.Settlements;
import dev.mizan.app.application.port.Transactions;
import dev.mizan.core.ledger.Entries;
import dev.mizan.core.settlement.MatchOutcome;
import dev.mizan.core.settlement.Matcher;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Matches one settlement line against what Mizan recorded. See docs/lld/reconciliation.md, "Step 2".
 *
 * <p>A line that does not fit gets one more chance: Mizan may have lost a capture, or not know a refund made in the
 * gateway's dashboard. So the payment is synced (rule R1), outside any transaction (rule R5), and the line is tried
 * once more. Only then is it a discrepancy.
 */
@Service
public class MatchLine {

  /** How long the matching worker leaves a line alone that it took, and waits before it tries a failed sync again. */
  public static final Duration LEASE = Duration.ofSeconds(30);

  private final Payments payments;
  private final Settlements settlements;
  private final SettlementWriter writer;
  private final SyncPayment sync;
  private final Transactions transactions;
  private final InstantSource clock;

  public MatchLine(
      Payments payments, Settlements settlements, SettlementWriter writer, SyncPayment sync, Transactions transactions, InstantSource clock) {
    this.payments = payments;
    this.settlements = settlements;
    this.writer = writer;
    this.sync = sync;
    this.transactions = transactions;
    this.clock = clock;
  }

  public void handle(UUID lineId) {
    UUID toSync = transactions.run(() -> attempt(lineId));
    if (toSync == null) {
      return;
    }
    if (!sync.handle(toSync).fetched()) {
      transactions.run(() -> settlements.retryAt(lineId, clock.instant().plus(LEASE))); // the gateway cannot be asked now
      return;
    }
    transactions.run(() -> {
      settlements.synced(lineId);
      return attempt(lineId); // a synced line is not sent to sync again: it matches now, or it is a discrepancy
    });
  }

  /** @return the payment to sync before the line is tried once more, or null when the line is handled */
  private UUID attempt(UUID lineId) {
    Instant now = clock.instant();
    LineRecord line = settlements.lockLine(lineId).orElseThrow();
    if (!line.status().waiting()) {
      return null; // handled already, by another worker
    }
    // The line first, then the order and the payment: the same order everywhere.
    Optional<PaymentRecord> payment = payments.findByGatewayId(line.gateway(), line.line().gatewayPaymentId())
        .flatMap(found -> payments.lock(found.id()));
    MatchOutcome outcome = Matcher.match(
        line.line(), payment.map(PaymentRecord::forSettlement).orElse(null), settlements.seenBefore(line), line.synced());

    switch (outcome) {
      case MatchOutcome.Matched matched ->
          writer.matched(line, payment.orElseThrow(), Entries.settle(line.id(), line.line().gross(), line.line().fee()), now);
      case MatchOutcome.NeedsSync needsSync -> {
        UUID paymentId = payment.orElseThrow().id();
        settlements.needsSync(lineId, paymentId, now);
        return paymentId;
      }
      case MatchOutcome.Mismatch mismatch -> writer.mismatch(line, payment.orElse(null), mismatch.type(), now);
    }
    return null;
  }
}
