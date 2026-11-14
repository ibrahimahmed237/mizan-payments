package dev.mizan.app.application.port;

import dev.mizan.app.application.model.IdempotencyRecord;
import java.time.Instant;
import java.util.Optional;

/** What each {@code Idempotency-Key} made. */
public interface IdempotencyKeys {

  Optional<IdempotencyRecord> find(String key);

  /**
   * Saves the key in the current transaction. If another transaction is saving the same key right now, this waits
   * for it to end.
   *
   * @return false when the key exists already; nothing is saved then
   */
  boolean save(IdempotencyRecord record, Instant now, Instant expiresAt);
}
