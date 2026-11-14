package dev.mizan.core.payment;

/** The two kinds of gateway Mizan talks to, and what each can do. */
public enum Gateway {
  /** Cards and wallets. The caller chooses the payment id; the gateway captures by itself when asked. */
  CARD_GATEWAY(true, true, false),
  /** Pay later. The provider chooses the id; Mizan sends the capture; refunds carry a key. */
  PAY_LATER(false, false, true);

  private final boolean capturesByItself;
  private final boolean partialCapture;
  private final boolean refundKey;

  Gateway(boolean capturesByItself, boolean partialCapture, boolean refundKey) {
    this.capturesByItself = capturesByItself;
    this.partialCapture = partialCapture;
    this.refundKey = refundKey;
  }

  /** With automatic capture, the gateway takes the money without a capture call from Mizan. */
  public boolean capturesByItself() {
    return capturesByItself;
  }

  public boolean supportsPartialCapture() {
    return partialCapture;
  }

  /** The same refund sent twice is done once. Only then may Mizan send a refund again by itself. */
  public boolean hasRefundKey() {
    return refundKey;
  }
}
