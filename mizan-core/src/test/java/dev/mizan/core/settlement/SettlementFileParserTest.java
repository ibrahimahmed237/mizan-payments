package dev.mizan.core.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mizan.core.payment.Gateway;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SettlementFileParserTest {

  private static final String CAPTURE = "L,txn_1,pay_1,capture,10000,250,9750,2026-11-14T09:00:00Z";
  private static final String REFUND = "L,txn_2,pay_1,refund,-3000,0,-3000,2026-11-14T10:00:00Z";

  private static String header(long total, int count) {
    return "H,payout_7,card_gateway,SAR,2026-11-15," + total + "," + count;
  }

  private static SettlementFile parse(String... records) {
    return SettlementFileParser.parse(new StringReader(String.join("\n", records)));
  }

  private static String refusal(String... records) {
    try {
      parse(records);
      return null;
    } catch (SettlementFileException e) {
      return e.code();
    }
  }

  @Test
  void reads_the_header_and_the_lines() {
    SettlementFile file = parse(header(6_750, 2), CAPTURE, REFUND, "");

    assertThat(file.header()).isEqualTo(
        new SettlementHeader("payout_7", Gateway.CARD_GATEWAY, "SAR", LocalDate.of(2026, 11, 15), 6_750, 2));
    assertThat(file.lines()).containsExactly(
        new SettlementLine(1, "txn_1", "pay_1", LineType.CAPTURE, "capture", 10_000, 250, 9_750, Instant.parse("2026-11-14T09:00:00Z")),
        new SettlementLine(2, "txn_2", "pay_1", LineType.REFUND, "refund", -3_000, 0, -3_000, Instant.parse("2026-11-14T10:00:00Z")));
  }

  @Test
  void a_byte_order_mark_at_the_start_is_not_part_of_the_file() {
    SettlementFile file = parse("\uFEFF" + header(9_750, 1), CAPTURE);

    assertThat(file.header().payoutId()).isEqualTo("payout_7");
    assertThat(refusal(header(9_750, 1), "\uFEFF" + CAPTURE)).isEqualTo("malformed_file"); // only at the very start
  }

  @Test
  void a_payout_with_no_lines_is_a_file() {
    assertThat(parse(header(0, 0)).lines()).isEmpty();
  }

  @Test
  void reads_the_pay_later_gateway_and_windows_line_ends() {
    SettlementFile file = SettlementFileParser.parse(
        new StringReader("H,p1,pay_later,SAR,2026-11-15,9750,1\r\n" + CAPTURE + "\r\n"));

    assertThat(file.header().gateway()).isEqualTo(Gateway.PAY_LATER);
    assertThat(file.lines()).hasSize(1);
  }

  @Test
  void a_line_of_another_type_is_kept_as_other_with_its_own_word() {
    SettlementFile file = parse(header(-5_000, 1), "L,txn_9,pay_1,chargeback,-5000,0,-5000,2026-11-14T09:00:00Z");

    assertThat(file.lines().get(0).type()).isEqualTo(LineType.OTHER);
    assertThat(file.lines().get(0).typeText()).isEqualTo("chargeback");
  }

  @Test
  void a_fee_returned_on_a_refund_is_allowed() {
    assertThat(parse(header(-2_925, 1), "L,txn_2,pay_1,refund,-3000,-75,-2925,2026-11-14T10:00:00Z").lines()).hasSize(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "",
    "L,txn_1,pay_1,capture,10000,250,9750,2026-11-14T09:00:00Z",
    "H,payout_7,card_gateway,SAR,2026-11-15,0",
    "H,payout_7,card_gateway,SAR,2026-11-15,0,0,extra",
    "H,payout 7,card_gateway,SAR,2026-11-15,0,0",
    "H,,card_gateway,SAR,2026-11-15,0,0",
    "H,payout_7,card_gateway,SAR,15/11/2026,0,0",
    "H,payout_7,card_gateway,SAR,2026-11-15,1.5,0",
    "H,payout_7,card_gateway,SAR,2026-11-15,0,-1",
    "H,payout_7,card_gateway,SAR,2026-11-15,0,99999999999",
    "H,payout_7,card_gateway,SAR,2026-11-15,0,x",
  })
  void a_header_that_cannot_be_read_refuses_the_file(String header) {
    assertThat(refusal(header)).isEqualTo("malformed_file");
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "X,txn_1,pay_1,capture,10000,250,9750,2026-11-14T09:00:00Z",
    "L,txn_1,pay_1,capture,10000,250,9750",
    "L,txn_1,pay_1,capture,10000,250,9750,2026-11-14T09:00:00Z,extra",
    "L,txn 1,pay_1,capture,10000,250,9750,2026-11-14T09:00:00Z",
    "L,txn_1,,capture,10000,250,9750,2026-11-14T09:00:00Z",
    "L,txn_1,pay_1,Capture!,10000,250,9750,2026-11-14T09:00:00Z",
    "L,txn_1,pay_1,capture,100.00,250,9750,2026-11-14T09:00:00Z",
    "L,txn_1,pay_1,capture,10000,2e2,9750,2026-11-14T09:00:00Z",
    "L,txn_1,pay_1,capture,10000,250,9750,yesterday",
    "L,txn_1,pay_1,capture,1234567890123456789012,250,9750,2026-11-14T09:00:00Z",
  })
  void a_line_that_cannot_be_read_refuses_the_file(String line) {
    assertThat(refusal(header(9_750, 1), line)).isEqualTo("malformed_file");
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
    "L,t,p,capture,0,0,0,2026-11-14T09:00:00Z          | 0",
    "L,t,p,capture,-100,0,-100,2026-11-14T09:00:00Z    | -100",
    "L,t,p,capture,100,-1,101,2026-11-14T09:00:00Z     | 101",
    "L,t,p,capture,100,101,-1,2026-11-14T09:00:00Z     | -1",
    "L,t,p,refund,0,0,0,2026-11-14T09:00:00Z           | 0",
    "L,t,p,refund,100,0,100,2026-11-14T09:00:00Z       | 100",
    "L,t,p,refund,-100,101,-201,2026-11-14T09:00:00Z   | -201",
    "L,t,p,refund,-100,-101,1,2026-11-14T09:00:00Z     | 1",
  })
  void amounts_that_do_not_fit_the_lines_type_refuse_the_file(String line, long total) {
    assertThat(refusal(header(total, 1), line.strip())).isEqualTo("malformed_file");
  }

  @Test
  void a_fee_as_large_as_the_capture_is_still_a_capture() {
    assertThat(parse(header(0, 1), "L,t,p,capture,100,100,0,2026-11-14T09:00:00Z").lines()).hasSize(1);
  }

  @Test
  void a_line_whose_net_is_not_gross_minus_fee_refuses_the_file() {
    assertThat(refusal(header(9_751, 1), "L,txn_1,pay_1,capture,10000,250,9751,2026-11-14T09:00:00Z")).isEqualTo("line_does_not_add_up");
  }

  @Test
  void a_wrong_line_count_refuses_the_file() {
    assertThat(refusal(header(6_750, 3), CAPTURE, REFUND)).isEqualTo("line_count_mismatch");
    assertThat(refusal(header(9_750, 0), CAPTURE)).isEqualTo("line_count_mismatch");
  }

  @Test
  void a_wrong_total_refuses_the_file() {
    assertThat(refusal(header(6_751, 2), CAPTURE, REFUND)).isEqualTo("payout_total_mismatch");
  }

  @Test
  void an_unknown_gateway_or_another_currency_refuses_the_file() {
    assertThat(refusal("H,payout_7,other_gateway,SAR,2026-11-15,0,0")).isEqualTo("wrong_gateway");
    assertThat(refusal("H,payout_7,card_gateway,USD,2026-11-15,0,0")).isEqualTo("wrong_currency");
  }

  @Test
  void a_file_with_too_many_lines_is_refused_before_it_is_all_read() {
    var source = new StringReader(String.join("\n", header(29_250, 3), CAPTURE, CAPTURE, CAPTURE));

    assertThatThrownBy(() -> SettlementFileParser.parse(source, 2))
        .isInstanceOfSatisfying(SettlementFileException.class, e -> assertThat(e.code()).isEqualTo("file_too_large"));
  }

  @Test
  void amounts_that_overflow_refuse_the_file() {
    String huge = "L,t1,p,capture,900000000000000000,0,900000000000000000,2026-11-14T09:00:00Z";

    assertThat(refusal(header(0, 11), huge, huge, huge, huge, huge, huge, huge, huge, huge, huge, huge)).isEqualTo("malformed_file");
  }

  @Test
  void a_source_that_fails_is_not_hidden_as_a_bad_file() {
    Reader broken = new Reader() {
      @Override
      public int read(char[] buffer, int offset, int length) throws IOException {
        throw new IOException("disk");
      }

      @Override
      public void close() {}
    };

    assertThatThrownBy(() -> SettlementFileParser.parse(broken)).isInstanceOf(UncheckedIOException.class);
  }
}
