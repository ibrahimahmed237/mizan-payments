package dev.mizan.core.ledger;

/** The six accounts of Mizan's books. */
public enum Account {
  /** Captured, not yet paid out by the gateway. */
  GATEWAY_RECEIVABLE("gateway_receivable"),
  /** Paid out by the gateway. */
  BANK("bank"),
  /** Kept by the gateway. */
  GATEWAY_FEES("gateway_fees"),
  /** Paid by customers, minus refunds. */
  SALES("sales"),
  /** Received and not explained yet. */
  SUSPENSE("suspense"),
  /** Differences that finance accepted. */
  ADJUSTMENTS("adjustments");

  private final String code;

  Account(String code) {
    this.code = code;
  }

  /** The name in the database and the API. */
  public String code() {
    return code;
  }
}
