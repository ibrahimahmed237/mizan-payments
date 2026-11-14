package dev.mizan.app.adapter.out.db;

import dev.mizan.app.application.port.Ledger;
import dev.mizan.core.ledger.Entry;
import dev.mizan.core.ledger.Posting;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The ledger tables. The database itself refuses an entry that does not balance, a change and a delete. */
@Repository
class JdbcLedger implements Ledger {

  private final JdbcClient jdbc;

  JdbcLedger(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean post(Entry entry, UUID paymentId, Instant at) {
    UUID id = UUID.randomUUID();
    int rows = jdbc.sql("""
        INSERT INTO ledger_entries (id, kind, dedupe_key, payment_id, created_at) VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (dedupe_key) DO NOTHING
        """)
        .param(id).param(entry.kind().name()).param(entry.key()).param(paymentId).param(Sql.time(at))
        .update();
    if (rows == 0) {
      return false; // this fact was written before
    }
    for (Posting posting : entry.postings()) {
      jdbc.sql("INSERT INTO ledger_postings (entry_id, account, side, amount) VALUES (?, ?, ?, ?)")
          .param(id).param(posting.account().code()).param(posting.side().name()).param(posting.amount())
          .update();
    }
    return true;
  }
}
