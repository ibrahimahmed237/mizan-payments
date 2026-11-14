package dev.mizan.core.settlement;

/** What a settlement line is for. Anything that is not a capture or a refund is OTHER, for example a chargeback. */
public enum LineType {
  CAPTURE,
  REFUND,
  OTHER;

  static LineType of(String text) {
    return switch (text) {
      case "capture" -> CAPTURE;
      case "refund" -> REFUND;
      default -> OTHER;
    };
  }
}
