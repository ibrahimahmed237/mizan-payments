package dev.mizan.app.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The checks that must hold for the ledger at every moment. See docs/lld/ledger.md, "What must always be true". */
public final class Books {

  private Books() {}

  /** Checks invariants I1, I2 and I3 over the whole database. */
  public static void check(JdbcClient jdbc) {
    // I1: total debits = total credits.
    long difference = jdbc.sql("SELECT coalesce(sum(CASE WHEN side = 'DEBIT' THEN amount ELSE -amount END), 0) FROM ledger_postings")
        .query(Long.class).single();
    assertThat(difference).as("I1: total debits minus total credits").isZero();

    // I2: for each payment, receivable = captured - refunded - settled capture + settled refund.
    List<UUID> wrong = jdbc.sql("""
        SELECT p.id
        FROM payments p
        LEFT JOIN (
          SELECT e.payment_id, sum(CASE WHEN l.side = 'DEBIT' THEN l.amount ELSE -l.amount END) AS balance
          FROM ledger_entries e JOIN ledger_postings l ON l.entry_id = e.id
          WHERE l.account = 'gateway_receivable'
          GROUP BY e.payment_id
        ) r ON r.payment_id = p.id
        WHERE coalesce(r.balance, 0) <> p.captured_amount - p.refunded_amount - p.settled_captured + p.settled_refunded
        """).query(UUID.class).list();
    assertThat(wrong).as("I2: payments whose receivable differs from their totals").isEmpty();

    // I3: sales = total captured - total refunded.
    long sales = balance(jdbc, "sales");
    long expected = jdbc.sql("SELECT coalesce(sum(captured_amount - refunded_amount), 0) FROM payments").query(Long.class).single();
    assertThat(-sales).as("I3: sales (a credit balance)").isEqualTo(expected);
  }

  /** An account's balance, debits minus credits. */
  public static long balance(JdbcClient jdbc, String account) {
    return jdbc.sql("SELECT coalesce(sum(CASE WHEN side = 'DEBIT' THEN amount ELSE -amount END), 0) FROM ledger_postings WHERE account = ?")
        .param(account).query(Long.class).single();
  }
}
