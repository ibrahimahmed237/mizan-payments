package dev.mizan.core.money;

import java.util.regex.Pattern;

/**
 * Amounts in Mizan are whole halalas in a {@code long} (1 SAR = 100 halalas). This class holds the few places where
 * money meets text, so that no other code converts by hand.
 */
public final class Money {

  /** The only currency Mizan accepts. */
  public static final String SAR = "SAR";

  private static final Pattern DECIMAL = Pattern.compile("(\\d{1,15})(?:\\.(\\d{2}))?");

  private Money() {}

  /**
   * Reads an amount written in riyals, such as {@code "150.00"} or {@code "150"}, exactly.
   *
   * @throws IllegalArgumentException when the text is not a whole number of halalas
   */
  public static long parseRiyals(String text) {
    var match = text == null ? null : DECIMAL.matcher(text.strip());
    if (match == null || !match.matches()) {
      throw new IllegalArgumentException("Not an amount in riyals with at most two decimals: " + text);
    }
    long riyals = Long.parseLong(match.group(1));
    long halalas = match.group(2) == null ? 0 : Long.parseLong(match.group(2));
    return Math.addExact(Math.multiplyExact(riyals, 100), halalas);
  }

  /** Writes halalas as riyals with two decimals: {@code 15000} becomes {@code "150.00"}. */
  public static String formatRiyals(long halalas) {
    long absolute = Math.abs(halalas);
    return (halalas < 0 ? "-" : "") + absolute / 100 + "." + String.format("%02d", absolute % 100);
  }

  /** Returns the amount, or throws when it is negative. */
  public static long requireNotNegative(long halalas, String name) {
    if (halalas < 0) {
      throw new IllegalArgumentException(name + " must not be negative: " + halalas);
    }
    return halalas;
  }

  /** Returns the amount, or throws when it is zero or negative. */
  public static long requirePositive(long halalas, String name) {
    if (halalas <= 0) {
      throw new IllegalArgumentException(name + " must be above zero: " + halalas);
    }
    return halalas;
  }
}
