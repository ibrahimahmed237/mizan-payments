package dev.mizan.app.application.port;

import dev.mizan.app.application.model.ApiKey;
import java.time.Instant;
import java.util.Optional;

/** Callers' keys. Only hashes are stored. */
public interface ApiKeys {

  Optional<ApiKey> findByHash(String keyHash);

  void insert(ApiKey key, String keyHash, Instant at);

  /** @return false when there is no such key, or it was revoked before */
  boolean revoke(String name, Instant at);
}
