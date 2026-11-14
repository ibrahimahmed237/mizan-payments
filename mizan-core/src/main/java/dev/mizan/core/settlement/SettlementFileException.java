package dev.mizan.core.settlement;

/** A settlement file that Mizan refuses as a whole. Nothing from it is saved. */
public final class SettlementFileException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String code;

  public SettlementFileException(String code, String message) {
    super(message);
    this.code = code;
  }

  /** A stable code for the API: {@code malformed_file}, {@code payout_total_mismatch}, and so on. */
  public String code() {
    return code;
  }
}
