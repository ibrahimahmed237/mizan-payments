package dev.mizan.app.application.model;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** A caller's key. Only the hash of the key is known. */
public record ApiKey(UUID id, String name, Set<String> scopes, Instant revokedAt) {

  public static final String PAYMENTS = "payments";
  public static final String BACKOFFICE = "backoffice";

  /** Whether the key has the scope. A revoked key is refused before this is asked. */
  public boolean allows(String scope) {
    return scopes.contains(scope);
  }
}
