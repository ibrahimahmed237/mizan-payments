package dev.mizan.core.ledger;

import java.util.Objects;

/** One side of an entry: an account, debit or credit, and an amount above zero in halalas. */
public record Posting(Account account, Side side, long amount) {

  public Posting {
    Objects.requireNonNull(account, "account");
    Objects.requireNonNull(side, "side");
    if (amount <= 0) {
      throw new IllegalArgumentException("A posting's amount is above zero: " + amount);
    }
  }
}
