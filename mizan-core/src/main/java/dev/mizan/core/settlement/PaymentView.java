package dev.mizan.core.settlement;

/**
 * What matching needs to know about a payment, in halalas.
 *
 * @param settledCaptured how much of {@code captured} earlier lines already paid out
 * @param settledRefunded how much of {@code refunded} earlier lines already took back
 */
public record PaymentView(long captured, long refunded, long settledCaptured, long settledRefunded) {

  public long unsettledCaptured() {
    return captured - settledCaptured;
  }

  public long unsettledRefunded() {
    return refunded - settledRefunded;
  }
}
