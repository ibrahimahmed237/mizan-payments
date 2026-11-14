package dev.mizan.core.settlement;

/** What matching decided for one line. */
public sealed interface MatchOutcome {

  /** The line fits what is still unsettled: post the settle entry. */
  record Matched() implements MatchOutcome {}

  /** The line does not fit yet. Sync the payment and try once more: Mizan may have lost a capture or a refund. */
  record NeedsSync() implements MatchOutcome {}

  /** The line does not fit: post its money to suspense and open this discrepancy. */
  record Mismatch(DiscrepancyType type) implements MatchOutcome {}
}
