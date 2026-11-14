package dev.mizan.core.settlement;

import dev.mizan.core.money.Money;
import dev.mizan.core.payment.Gateway;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads the neutral settlement format, one line at a time, and checks the file as a whole:
 *
 * <pre>
 * H,payout_id,gateway,currency,payout_date,total_net,line_count
 * L,transaction_id,payment_id,type,gross,fee,net,occurred_at
 * </pre>
 *
 * A file that fails any check is refused entirely: Mizan posts nothing from a file it cannot trust.
 */
public final class SettlementFileParser {

  /** No real payout has more lines; a larger file is a mistake or an attack. */
  public static final int MAX_LINES = 500_000;

  private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
  private static final Pattern TYPE = Pattern.compile("[a-z_]{1,32}");

  private SettlementFileParser() {}

  public static SettlementFile parse(Reader source) {
    return parse(source, MAX_LINES);
  }

  static SettlementFile parse(Reader source, int maxLines) {
    try (var reader = new BufferedReader(source)) {
      String first = readLine(reader);
      if (first == null) {
        throw malformed("The file is empty.");
      }
      SettlementHeader header = header(first);
      List<SettlementLine> lines = new ArrayList<>();
      long sum = 0;
      String text;
      while ((text = readLine(reader)) != null) {
        if (text.isEmpty()) {
          continue; // a final empty line is common
        }
        if (lines.size() >= maxLines) {
          throw new SettlementFileException("file_too_large", "The file has more than " + maxLines + " lines.");
        }
        SettlementLine line = line(text, lines.size() + 1);
        sum = Math.addExact(sum, line.net());
        lines.add(line);
      }
      if (lines.size() != header.lineCount()) {
        throw new SettlementFileException(
            "line_count_mismatch", "The header says " + header.lineCount() + " lines; the file has " + lines.size() + ".");
      }
      if (sum != header.totalNet()) {
        throw new SettlementFileException(
            "payout_total_mismatch", "The lines add up to " + sum + " halalas; the header says " + header.totalNet() + ".");
      }
      return new SettlementFile(header, lines);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (ArithmeticException e) {
      throw malformed("An amount is too large.");
    }
  }

  private static String readLine(BufferedReader reader) throws IOException {
    String line = reader.readLine();
    return line == null ? null : line.strip();
  }

  private static SettlementHeader header(String text) {
    String[] f = text.split(",", -1);
    if (f.length != 7 || !f[0].equals("H")) {
      throw malformed("The first record must be the header: H,payout_id,gateway,currency,payout_date,total_net,line_count.");
    }
    Gateway gateway = switch (f[2]) {
      case "card_gateway" -> Gateway.CARD_GATEWAY;
      case "pay_later" -> Gateway.PAY_LATER;
      default -> throw new SettlementFileException("wrong_gateway", "Unknown gateway in the header.");
    };
    if (!Money.SAR.equals(f[3])) {
      throw new SettlementFileException("wrong_currency", "Only SAR is accepted.");
    }
    long lineCount = number(f[6], "line_count");
    if (lineCount < 0 || lineCount > Integer.MAX_VALUE) {
      throw malformed("line_count is not a count.");
    }
    return new SettlementHeader(id(f[1], "payout_id"), gateway, f[3], date(f[4]), number(f[5], "total_net"), (int) lineCount);
  }

  private static SettlementLine line(String text, int number) {
    String[] f = text.split(",", -1);
    if (f.length != 8 || !f[0].equals("L")) {
      throw malformed("Line " + number + " must be: L,transaction_id,payment_id,type,gross,fee,net,occurred_at.");
    }
    if (!TYPE.matcher(f[3]).matches()) {
      throw malformed("Line " + number + " has a type that cannot be read.");
    }
    LineType type = LineType.of(f[3]);
    long gross = number(f[4], "gross");
    long fee = number(f[5], "fee");
    long net = number(f[6], "net");
    if (net != Math.subtractExact(gross, fee)) {
      throw new SettlementFileException("line_does_not_add_up", "Line " + number + ": net is not gross minus fee.");
    }
    boolean signsFit = switch (type) {
      case CAPTURE -> gross > 0 && fee >= 0 && fee <= gross;
      case REFUND -> gross < 0 && Math.abs(fee) <= -gross;
      case OTHER -> true;
    };
    if (!signsFit) {
      throw malformed("Line " + number + ": the amounts do not fit a " + f[3] + ".");
    }
    return new SettlementLine(number, id(f[1], "transaction_id"), id(f[2], "payment_id"), type, f[3], gross, fee, net, instant(f[7], number));
  }

  private static String id(String text, String name) {
    if (!ID.matcher(text).matches()) {
      throw malformed(name + " cannot be read.");
    }
    return text;
  }

  private static long number(String text, String name) {
    if (!text.matches("-?\\d{1,18}")) {
      throw malformed(name + " is not a whole number of halalas.");
    }
    return Long.parseLong(text);
  }

  private static LocalDate date(String text) {
    try {
      return LocalDate.parse(text);
    } catch (DateTimeParseException e) {
      throw malformed("payout_date is not a date like 2026-11-14.");
    }
  }

  private static Instant instant(String text, int number) {
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException e) {
      throw malformed("Line " + number + ": occurred_at is not a time like 2026-11-14T09:00:00Z.");
    }
  }

  private static SettlementFileException malformed(String message) {
    return new SettlementFileException("malformed_file", message);
  }
}
