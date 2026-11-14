package dev.mizan.app.application;

import dev.mizan.app.application.port.Housekeeping;
import dev.mizan.app.application.port.Transactions;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.function.IntSupplier;
import org.springframework.stereotype.Service;

/**
 * The daily clean-up of docs/lld/data.md, "Kept how long": idempotency keys after 30 days, webhooks and delivered
 * events after 90 days. Payments, operations, the ledger, settlement files and discrepancies are kept for ever.
 * Rows are deleted in small batches, each in its own short transaction, so the clean-up never holds a lock long.
 */
@Service
public class CleanUp {

  /** @param idempotencyKeys keys deleted; @param webhooks webhook events deleted; @param events store events deleted */
  public record Result(int idempotencyKeys, int webhooks, int events) {}

  public static final Duration KEPT = Duration.ofDays(90);
  static final int BATCH = 1_000;

  private final Housekeeping housekeeping;
  private final Transactions transactions;
  private final InstantSource clock;

  public CleanUp(Housekeeping housekeeping, Transactions transactions, InstantSource clock) {
    this.housekeeping = housekeeping;
    this.transactions = transactions;
    this.clock = clock;
  }

  public Result handle() {
    Instant now = clock.instant();
    Instant before = now.minus(KEPT);
    return new Result(
        inBatches(() -> housekeeping.deleteIdempotencyKeysExpiredBy(now, BATCH)),
        inBatches(() -> housekeeping.deleteWebhookEventsReceivedBefore(before, BATCH)),
        inBatches(() -> housekeeping.deleteDoneEventsCreatedBefore(before, BATCH)));
  }

  private int inBatches(IntSupplier batch) {
    int total = 0;
    int deleted;
    do {
      deleted = transactions.run(batch::getAsInt);
      total += deleted;
    } while (deleted == BATCH);
    return total;
  }
}
