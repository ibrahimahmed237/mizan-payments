package dev.mizan.core.fees;

/**
 * What the gateway keeps from a capture, from the store's contract.
 *
 * <pre>
 * base = round_half_up(gross × percent) + fixed
 * fee  = base + round_half_up(base × VAT)
 * </pre>
 *
 * This is the one place in Mizan where an amount is rounded.
 *
 * @param percentBps the percentage in hundredths of a percent: 250 is 2.5%
 * @param fixed halalas added to every capture
 * @param vatBps VAT on the fee, in hundredths of a percent: 1500 is 15%
 */
public record FeeRule(int percentBps, long fixed, int vatBps) {

  private static final long SCALE = 10_000;

  public FeeRule {
    if (percentBps < 0 || percentBps > SCALE || fixed < 0 || vatBps < 0 || vatBps > SCALE) {
      throw new IllegalArgumentException("A fee rule has a percentage and a VAT from 0% to 100%, and a fixed part of zero or more.");
    }
  }

  /** The fee of a capture of {@code gross} halalas. */
  public long feeFor(long gross) {
    if (gross <= 0) {
      throw new IllegalArgumentException("A fee is for an amount above zero: " + gross);
    }
    long base = roundHalfUp(Math.multiplyExact(gross, percentBps)) + fixed;
    return base + roundHalfUp(Math.multiplyExact(base, vatBps));
  }

  private static long roundHalfUp(long scaled) {
    return (scaled + SCALE / 2) / SCALE;
  }
}
