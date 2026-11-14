package dev.mizan.core.ledger;

import java.util.List;
import java.util.Objects;

/**
 * One money fact. Its debits equal its credits, or it cannot be built.
 *
 * @param key unique per fact: the same fact recorded again has the same key and is written once
 */
public record Entry(EntryKind kind, String key, List<Posting> postings) {

  public Entry {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(key, "key");
    postings = List.copyOf(postings);
    if (postings.isEmpty()) {
      throw new IllegalArgumentException("An entry has postings.");
    }
    if (total(postings, Side.DEBIT) != total(postings, Side.CREDIT)) {
      throw new IllegalArgumentException("An entry's debits equal its credits: " + postings);
    }
  }

  public long total() {
    return total(postings, Side.DEBIT);
  }

  private static long total(List<Posting> postings, Side side) {
    return postings.stream().filter(p -> p.side() == side).mapToLong(Posting::amount).sum();
  }
}
