package dev.mizan.core.ledger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds the entry of each money fact. See docs/lld/ledger.md for the table.
 *
 * <p>Settlement entries are built from signed amounts, so one rule covers a capture line, a refund line and a
 * returned fee: an account is debited when its change is positive on its natural side and credited when negative.
 * An entry in which nothing moves is not written, so those methods return an empty result.
 */
public final class Entries {

  private Entries() {}

  /** Money taken. The key uses the total after the capture, so the same capture has one key. */
  public static Entry capture(UUID paymentId, long amount, long capturedAfter) {
    return new Entry(
        EntryKind.CAPTURE,
        "capture:" + paymentId + ":" + capturedAfter,
        List.of(new Posting(Account.GATEWAY_RECEIVABLE, Side.DEBIT, amount), new Posting(Account.SALES, Side.CREDIT, amount)));
  }

  /** Money given back. */
  public static Entry refund(UUID paymentId, long amount, long refundedAfter) {
    return new Entry(
        EntryKind.REFUND,
        "refund:" + paymentId + ":" + refundedAfter,
        List.of(new Posting(Account.SALES, Side.DEBIT, amount), new Posting(Account.GATEWAY_RECEIVABLE, Side.CREDIT, amount)));
  }

  /**
   * A settlement line that matched. {@code gross} is positive for a capture and negative for a refund; {@code fee}
   * is what the gateway kept, negative when it returned a fee. The bank gets {@code gross − fee}.
   */
  public static Optional<Entry> settle(UUID lineId, long gross, long fee) {
    return build(EntryKind.SETTLE, "line:" + lineId, debit(Account.BANK, gross - fee), debit(Account.GATEWAY_FEES, fee),
        credit(Account.GATEWAY_RECEIVABLE, gross));
  }

  /** A settlement line that did not match: only what reached the bank, held in suspense. */
  public static Optional<Entry> suspense(UUID lineId, long net) {
    return build(EntryKind.SUSPENSE, "line:" + lineId, debit(Account.BANK, net), credit(Account.SUSPENSE, net));
  }

  /** A suspense line that matched later. The bank side was posted already; the fee is known now. */
  public static Optional<Entry> rematch(UUID lineId, long gross, long fee) {
    return build(EntryKind.REMATCH, "rematch:" + lineId, debit(Account.SUSPENSE, gross - fee), debit(Account.GATEWAY_FEES, fee),
        credit(Account.GATEWAY_RECEIVABLE, gross));
  }

  /** Finance accepted a difference: the money leaves suspense for adjustments. */
  public static Optional<Entry> accept(UUID discrepancyId, long net) {
    return build(EntryKind.ACCEPT, "accept:" + discrepancyId, debit(Account.SUSPENSE, net), credit(Account.ADJUSTMENTS, net));
  }

  /** A signed change on the debit side: positive debits, negative credits. */
  private static SignedPosting debit(Account account, long amount) {
    return new SignedPosting(account, amount);
  }

  /** A signed change on the credit side: positive credits, negative debits. */
  private static SignedPosting credit(Account account, long amount) {
    return new SignedPosting(account, -amount);
  }

  private static Optional<Entry> build(EntryKind kind, String key, SignedPosting... changes) {
    List<Posting> postings = new ArrayList<>();
    for (SignedPosting change : changes) {
      if (change.debit > 0) {
        postings.add(new Posting(change.account, Side.DEBIT, change.debit));
      } else if (change.debit < 0) {
        postings.add(new Posting(change.account, Side.CREDIT, -change.debit));
      }
    }
    return postings.isEmpty() ? Optional.empty() : Optional.of(new Entry(kind, key, postings));
  }

  /** A change to an account, positive when it is a debit. */
  private record SignedPosting(Account account, long debit) {}
}
