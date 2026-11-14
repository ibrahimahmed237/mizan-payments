package dev.mizan.app.adapter.out.db;

import dev.mizan.app.application.model.ApiKey;
import dev.mizan.app.application.port.ApiKeys;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The API keys table. It holds hashes, never keys. */
@Repository
class JdbcApiKeys implements ApiKeys {

  private final JdbcClient jdbc;

  JdbcApiKeys(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<ApiKey> findByHash(String keyHash) {
    return jdbc.sql("SELECT id, name, scopes, revoked_at FROM api_keys WHERE key_hash = ?")
        .param(keyHash)
        .query((row, n) -> new ApiKey(
            Sql.uuid(row, "id"), row.getString("name"), Set.of(row.getString("scopes").split(",")), Sql.instant(row, "revoked_at")))
        .optional();
  }

  @Override
  public void insert(ApiKey key, String keyHash, Instant at) {
    jdbc.sql("INSERT INTO api_keys (id, name, key_hash, scopes, created_at) VALUES (?, ?, ?, ?, ?)")
        .param(key.id()).param(key.name()).param(keyHash).param(String.join(",", key.scopes().stream().sorted().toList()))
        .param(Sql.time(at))
        .update();
  }

  @Override
  public boolean revoke(String name, Instant at) {
    return jdbc.sql("UPDATE api_keys SET revoked_at = ? WHERE name = ? AND revoked_at IS NULL").param(Sql.time(at)).param(name).update() > 0;
  }
}
