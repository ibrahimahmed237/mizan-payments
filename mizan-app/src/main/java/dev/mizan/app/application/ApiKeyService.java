package dev.mizan.app.application;

import dev.mizan.app.application.model.ApiKey;
import dev.mizan.app.application.port.ApiKeys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.InstantSource;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Callers' keys. A key is 32 random bytes, shown once when it is made. Only its SHA-256 hash is stored, and a key
 * is looked up by that hash, so no comparison can leak how much of a guess was right.
 */
@Service
public class ApiKeyService {

  private static final Set<String> SCOPES = Set.of(ApiKey.PAYMENTS, ApiKey.BACKOFFICE);

  private final ApiKeys keys;
  private final InstantSource clock;
  private final SecureRandom random = new SecureRandom();

  public ApiKeyService(ApiKeys keys, InstantSource clock) {
    this.keys = keys;
    this.clock = clock;
  }

  /** Makes a key and returns it. This is the only time it can be read. */
  public String create(String name, Set<String> scopes) {
    if (name == null || name.isBlank() || scopes.isEmpty() || !SCOPES.containsAll(scopes)) {
      throw new IllegalArgumentException("A key needs a name and at least one scope of " + SCOPES + ".");
    }
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String key = "mzn_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    keys.insert(new ApiKey(UUID.randomUUID(), name, Set.copyOf(scopes), null), hash(key), clock.instant());
    return key;
  }

  public boolean revoke(String name) {
    return keys.revoke(name, clock.instant());
  }

  /** The caller behind a key, if the key is known. A revoked key is known but allows nothing. */
  public Optional<ApiKey> find(String key) {
    return keys.findByHash(hash(key));
  }

  private static String hash(String key) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
