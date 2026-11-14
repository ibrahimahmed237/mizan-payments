package dev.mizan.app.application.port;

import dev.mizan.core.ledger.Entry;
import java.time.Instant;
import java.util.UUID;

/** The ledger tables. Append-only. */
public interface Ledger {

  /**
   * Writes an entry in the current transaction.
   *
   * @param paymentId the payment the entry belongs to; null for an entry without one
   * @return false when the entry's key exists already: the fact was written before, and nothing is written now
   */
  boolean post(Entry entry, UUID paymentId, Instant at);
}
