package dev.mizan.app.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Reads the books. */
public interface LedgerQueries {

  /** One side of an entry. */
  record PostingRow(String account, String side, long amount) {}

  /** One entry with its postings. */
  record EntryRow(UUID id, String kind, String key, UUID paymentId, Instant createdAt, List<PostingRow> postings) {}

  /** Each account's total debits and total credits. Accounts with no postings are left out. */
  Map<String, long[]> totals();

  /** A payment's entries, oldest first. */
  List<EntryRow> entriesOf(UUID paymentId);
}
