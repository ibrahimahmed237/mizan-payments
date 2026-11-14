package dev.mizan.app.adapter.out.db;

import dev.mizan.app.application.port.LedgerQueries;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads the ledger tables. */
@Repository
class JdbcLedgerQueries implements LedgerQueries {

  private final JdbcClient jdbc;

  JdbcLedgerQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Map<String, long[]> totals() {
    Map<String, long[]> totals = new HashMap<>();
    jdbc.sql("""
        SELECT account,
               coalesce(sum(amount) FILTER (WHERE side = 'DEBIT'), 0) AS debits,
               coalesce(sum(amount) FILTER (WHERE side = 'CREDIT'), 0) AS credits
        FROM ledger_postings GROUP BY account
        """)
        .query((row, n) -> totals.put(row.getString("account"), new long[] {row.getLong("debits"), row.getLong("credits")})).list();
    return totals;
  }

  @Override
  public List<EntryRow> entriesOf(UUID paymentId) {
    Map<UUID, EntryRow> entries = new LinkedHashMap<>();
    jdbc.sql("""
        SELECT e.id, e.kind, e.dedupe_key, e.payment_id, e.created_at, p.account, p.side, p.amount
        FROM ledger_entries e JOIN ledger_postings p ON p.entry_id = e.id
        WHERE e.payment_id = ? ORDER BY e.seq, p.id
        """)
        .param(paymentId)
        .query((row, n) -> {
          UUID id = Sql.uuid(row, "id");
          EntryRow entry = entries.get(id);
          if (entry == null) {
            entry = new EntryRow(id, row.getString("kind"), row.getString("dedupe_key"), Sql.uuid(row, "payment_id"),
                Sql.instant(row, "created_at"), new ArrayList<>());
            entries.put(id, entry);
          }
          entry.postings().add(new PostingRow(row.getString("account"), row.getString("side"), row.getLong("amount")));
          return id;
        })
        .list();
    return List.copyOf(entries.values());
  }
}
