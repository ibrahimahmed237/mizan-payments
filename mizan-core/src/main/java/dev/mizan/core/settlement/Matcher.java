package dev.mizan.core.settlement;

import dev.mizan.core.fees.FeeRule;
import java.util.OptionalLong;

/** Decides whether a settlement line is money Mizan expected. See docs/lld/reconciliation.md. */
public final class Matcher {

  private Matcher() {}

  /**
   * @param payment the line's payment, or null when Mizan has none
   * @param transactionSeenBefore an earlier line had the same gateway transaction id
   * @param syncedAlready the payment was synced since the line first did not fit
   */
  public static MatchOutcome match(SettlementLine line, PaymentView payment, boolean transactionSeenBefore, boolean syncedAlready) {
    if (line.type() == LineType.OTHER) {
      return new MatchOutcome.Mismatch(DiscrepancyType.UNKNOWN_LINE_TYPE);
    }
    if (transactionSeenBefore) {
      return new MatchOutcome.Mismatch(DiscrepancyType.DUPLICATE_SETTLEMENT);
    }
    if (payment == null) {
      return new MatchOutcome.Mismatch(DiscrepancyType.UNKNOWN_PAYMENT);
    }
    boolean fits = line.type() == LineType.CAPTURE
        ? line.gross() <= payment.unsettledCaptured()
        : -line.gross() <= payment.unsettledRefunded();
    if (fits) {
      return new MatchOutcome.Matched();
    }
    if (!syncedAlready) {
      return new MatchOutcome.NeedsSync();
    }
    if (line.type() == LineType.REFUND) {
      return new MatchOutcome.Mismatch(DiscrepancyType.UNKNOWN_REFUND);
    }
    return new MatchOutcome.Mismatch(
        payment.captured() == 0 ? DiscrepancyType.NOT_CAPTURED_HERE : DiscrepancyType.AMOUNT_MISMATCH);
  }

  /**
   * The fee Mizan expected for a matched line, when it differs from the line's fee. A capture is checked against
   * the fee rule; a refund is expected to cost nothing.
   *
   * @return the expected fee when the line's fee is another amount; empty when they agree
   */
  public static OptionalLong unexpectedFee(SettlementLine line, FeeRule rule) {
    long expected = line.type() == LineType.CAPTURE ? rule.feeFor(line.gross()) : 0;
    return expected == line.fee() ? OptionalLong.empty() : OptionalLong.of(expected);
  }
}
