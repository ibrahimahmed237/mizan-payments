package dev.mizan.core.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class EntriesTest {

  private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

  private static Posting debit(Account account, long amount) {
    return new Posting(account, Side.DEBIT, amount);
  }

  private static Posting credit(Account account, long amount) {
    return new Posting(account, Side.CREDIT, amount);
  }

  @Test
  void a_capture_is_owed_by_the_gateway_and_counted_as_sales() {
    Entry entry = Entries.capture(ID, 10_000, 10_000);

    assertThat(entry.kind()).isEqualTo(EntryKind.CAPTURE);
    assertThat(entry.key()).isEqualTo("capture:" + ID + ":10000");
    assertThat(entry.postings()).containsExactly(debit(Account.GATEWAY_RECEIVABLE, 10_000), credit(Account.SALES, 10_000));
    assertThat(entry.total()).isEqualTo(10_000);
  }

  @Test
  void a_refund_is_the_reverse() {
    Entry entry = Entries.refund(ID, 3_000, 5_000);

    assertThat(entry.key()).isEqualTo("refund:" + ID + ":5000");
    assertThat(entry.postings()).containsExactly(debit(Account.SALES, 3_000), credit(Account.GATEWAY_RECEIVABLE, 3_000));
  }

  @Test
  void the_key_uses_the_total_after_the_fact_so_two_captures_differ_and_one_capture_does_not() {
    assertThat(Entries.capture(ID, 4_000, 4_000).key()).isNotEqualTo(Entries.capture(ID, 4_000, 8_000).key());
    assertThat(Entries.capture(ID, 4_000, 4_000).key()).isEqualTo(Entries.capture(ID, 4_000, 4_000).key());
  }

  @Test
  void a_settled_capture_pays_the_bank_the_net_and_books_the_fee() {
    Entry entry = Entries.settle(ID, 10_000, 250).orElseThrow();

    assertThat(entry.kind()).isEqualTo(EntryKind.SETTLE);
    assertThat(entry.key()).isEqualTo("line:" + ID);
    assertThat(entry.postings()).containsExactly(
        debit(Account.BANK, 9_750), debit(Account.GATEWAY_FEES, 250), credit(Account.GATEWAY_RECEIVABLE, 10_000));
  }

  @Test
  void a_settled_capture_without_a_fee_has_no_fee_posting() {
    assertThat(Entries.settle(ID, 10_000, 0).orElseThrow().postings())
        .containsExactly(debit(Account.BANK, 10_000), credit(Account.GATEWAY_RECEIVABLE, 10_000));
  }

  @Test
  void a_settled_refund_takes_the_money_out_of_the_bank() {
    assertThat(Entries.settle(ID, -3_000, 0).orElseThrow().postings())
        .containsExactly(credit(Account.BANK, 3_000), debit(Account.GATEWAY_RECEIVABLE, 3_000));
  }

  @Test
  void a_fee_returned_on_a_refund_line_reduces_the_fee_expense() {
    assertThat(Entries.settle(ID, -3_000, -75).orElseThrow().postings())
        .containsExactly(credit(Account.BANK, 2_925), credit(Account.GATEWAY_FEES, 75), debit(Account.GATEWAY_RECEIVABLE, 3_000));
  }

  @Test
  void a_line_that_did_not_match_posts_only_what_reached_the_bank_to_suspense() {
    Entry in = Entries.suspense(ID, 4_900).orElseThrow();
    Entry out = Entries.suspense(ID, -3_000).orElseThrow();

    assertThat(in.kind()).isEqualTo(EntryKind.SUSPENSE);
    assertThat(in.key()).isEqualTo("line:" + ID);
    assertThat(in.postings()).containsExactly(debit(Account.BANK, 4_900), credit(Account.SUSPENSE, 4_900));
    assertThat(out.postings()).containsExactly(credit(Account.BANK, 3_000), debit(Account.SUSPENSE, 3_000));
  }

  @Test
  void a_line_gets_one_entry_whether_it_matched_or_not() {
    assertThat(Entries.settle(ID, 100, 1).orElseThrow().key()).isEqualTo(Entries.suspense(ID, 99).orElseThrow().key());
  }

  @Test
  void a_suspense_line_that_matched_later_moves_to_the_receivable_and_books_its_fee() {
    Entry capture = Entries.rematch(ID, 10_000, 250).orElseThrow();
    Entry refund = Entries.rematch(ID, -3_000, 0).orElseThrow();

    assertThat(capture.kind()).isEqualTo(EntryKind.REMATCH);
    assertThat(capture.key()).isEqualTo("rematch:" + ID);
    assertThat(capture.postings()).containsExactly(
        debit(Account.SUSPENSE, 9_750), debit(Account.GATEWAY_FEES, 250), credit(Account.GATEWAY_RECEIVABLE, 10_000));
    assertThat(refund.postings()).containsExactly(credit(Account.SUSPENSE, 3_000), debit(Account.GATEWAY_RECEIVABLE, 3_000));
  }

  @Test
  void accepting_a_difference_moves_the_money_from_suspense_to_adjustments() {
    Entry in = Entries.accept(ID, 4_900).orElseThrow();
    Entry out = Entries.accept(ID, -3_000).orElseThrow();

    assertThat(in.key()).isEqualTo("accept:" + ID);
    assertThat(in.postings()).containsExactly(debit(Account.SUSPENSE, 4_900), credit(Account.ADJUSTMENTS, 4_900));
    assertThat(out.postings()).containsExactly(credit(Account.SUSPENSE, 3_000), debit(Account.ADJUSTMENTS, 3_000));
  }

  @Test
  void an_entry_in_which_nothing_moves_is_not_written() {
    assertThat(Entries.settle(ID, 0, 0)).isEmpty();
    assertThat(Entries.suspense(ID, 0)).isEmpty();
    assertThat(Entries.rematch(ID, 0, 0)).isEmpty();
    assertThat(Entries.accept(ID, 0)).isEmpty();
  }

  @Test
  void suspense_then_rematch_ends_like_a_line_that_matched_at_once() {
    Map<Account, Long> matchedAtOnce = balances(List.of(Entries.settle(ID, 10_000, 250).orElseThrow()));
    Map<Account, Long> matchedLater =
        balances(List.of(Entries.suspense(ID, 9_750).orElseThrow(), Entries.rematch(ID, 10_000, 250).orElseThrow()));

    assertThat(matchedLater).isEqualTo(matchedAtOnce);
  }

  @Test
  void an_unbalanced_entry_cannot_be_built() {
    assertThatThrownBy(() -> new Entry(EntryKind.CAPTURE, "k", List.of(debit(Account.BANK, 100), credit(Account.SALES, 99))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Entry(EntryKind.CAPTURE, "k", List.of())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Posting(Account.BANK, Side.DEBIT, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Posting(Account.BANK, Side.DEBIT, -1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void every_account_has_its_name() {
    assertThat(Account.values()).extracting(Account::code)
        .containsExactly("gateway_receivable", "bank", "gateway_fees", "sales", "suspense", "adjustments");
  }

  @Property(tries = 2000)
  void every_settlement_entry_balances_and_pays_the_bank_the_net(
      @ForAll @LongRange(min = -1_000_000, max = 1_000_000) long gross, @ForAll @LongRange(min = -5_000, max = 5_000) long fee) {
    long net = gross - fee;

    Map<Account, Long> settled = balances(Entries.settle(ID, gross, fee).stream().toList());
    Map<Account, Long> viaSuspense = balances(
        java.util.stream.Stream.concat(Entries.suspense(ID, net).stream(), Entries.rematch(ID, gross, fee).stream()).toList());

    assertThat(settled.getOrDefault(Account.BANK, 0L)).isEqualTo(net);
    assertThat(settled.getOrDefault(Account.GATEWAY_FEES, 0L)).isEqualTo(fee);
    assertThat(settled.getOrDefault(Account.GATEWAY_RECEIVABLE, 0L)).isEqualTo(-gross);
    assertThat(viaSuspense).isEqualTo(settled);
  }

  /** Each account's balance, debits positive. Zero balances are left out. Fails if the entries do not balance. */
  private static Map<Account, Long> balances(List<Entry> entries) {
    Map<Account, Long> balances = new EnumMap<>(Account.class);
    long sum = 0;
    for (Entry entry : entries) {
      for (Posting posting : entry.postings()) {
        long signed = posting.side() == Side.DEBIT ? posting.amount() : -posting.amount();
        balances.merge(posting.account(), signed, Long::sum);
        sum += signed;
      }
    }
    assertThat(sum).as("debits minus credits").isZero();
    balances.values().removeIf(balance -> balance == 0);
    return balances;
  }
}
