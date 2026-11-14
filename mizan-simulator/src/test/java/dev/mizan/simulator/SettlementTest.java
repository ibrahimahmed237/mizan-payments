package dev.mizan.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mizan.core.payment.Gateway;
import dev.mizan.core.settlement.LineType;
import dev.mizan.core.settlement.SettlementFile;
import dev.mizan.core.settlement.SettlementFileException;
import dev.mizan.core.settlement.SettlementFileParser;
import dev.mizan.core.settlement.SettlementLine;
import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The simulator's settlement files, read with Mizan's own parser, and each way the simulator can break them. */
class SettlementTest {

  private static final LocalDate DAY = LocalDate.of(2026, 11, 15);

  private final Kit kit = new Kit();

  @AfterEach
  void stop() {
    kit.close();
  }

  private long fee(long gross) {
    return kit.simulator.config().cardFee().feeFor(gross);
  }

  private SettlementFile payout(LocalDate date) {
    return SettlementFileParser.parse(new StringReader(kit.simulator.closePayout(Gateway.CARD_GATEWAY, date)));
  }

  private static SettlementLine lineOf(SettlementFile file, String paymentId, LineType type) {
    return file.lines().stream().filter(l -> l.gatewayPaymentId().equals(paymentId) && l.type() == type).findFirst().orElseThrow();
  }

  @Test
  void a_payout_has_one_line_per_capture_and_refund_and_its_total_is_what_reached_the_bank() throws IOException {
    String paid = kit.cardPayment("1111", 15_000, false, "order-1");
    String refunded = kit.cardPayment("1111", 20_000, false, "order-2");
    kit.card("POST", "/payments/" + refunded + "/refund", "{\"amount\":5000}");
    kit.cardPayment("3333", 9_000, false, "order-3"); // never paid: not in the file

    SettlementFile file = payout(DAY);

    assertThat(file.header().payoutId()).isEqualTo("po_card_2026-11-15");
    assertThat(file.header().gateway()).isEqualTo(Gateway.CARD_GATEWAY);
    assertThat(file.lines()).hasSize(3);
    assertThat(lineOf(file, paid, LineType.CAPTURE).gross()).isEqualTo(15_000);
    assertThat(lineOf(file, paid, LineType.CAPTURE).fee()).isEqualTo(fee(15_000));
    assertThat(lineOf(file, refunded, LineType.REFUND).gross()).isEqualTo(-5_000);
    assertThat(lineOf(file, refunded, LineType.REFUND).fee()).isZero();
    long total = 15_000 - fee(15_000) + 20_000 - fee(20_000) - 5_000;
    assertThat(file.header().totalNet()).isEqualTo(total);
    assertThat(kit.simulator.bankTotal()).isEqualTo(total);
  }

  @Test
  void the_same_date_again_gives_the_same_file_and_a_later_date_only_what_is_new() throws IOException {
    kit.cardPayment("1111", 15_000, false, "order-1");
    String first = kit.simulator.closePayout(Gateway.CARD_GATEWAY, DAY);
    String later = kit.cardPayment("1111", 7_000, false, "order-2");

    assertThat(kit.simulator.closePayout(Gateway.CARD_GATEWAY, DAY)).isEqualTo(first);
    SettlementFile next = payout(DAY.plusDays(1));
    assertThat(next.lines()).hasSize(1);
    assertThat(next.lines().get(0).gatewayPaymentId()).isEqualTo(later);
  }

  @Test
  void each_gateway_has_its_own_payout_and_the_file_is_served_over_http() throws IOException {
    kit.cardPayment("1111", 15_000, false, "order-1");
    String payLater = kit.payLaterPayment("150.00", "order-2");
    kit.simulator.customerApproves(payLater);
    kit.payLater("POST", "/payments/" + payLater + "/captures", "{\"amount\":\"150.00\"}");

    SettlementFile card = SettlementFileParser.parse(new StringReader(kit.card("GET", "/settlements/" + DAY, null).text()));
    SettlementFile later = SettlementFileParser.parse(new StringReader(kit.payLater("GET", "/settlements/" + DAY, null).text()));

    assertThat(card.lines()).hasSize(1);
    assertThat(later.header().gateway()).isEqualTo(Gateway.PAY_LATER);
    assertThat(later.lines()).hasSize(1);
    assertThat(later.lines().get(0).fee()).isEqualTo(kit.simulator.config().payLaterFee().feeFor(15_000));
  }

  @Test
  void a_payout_with_nothing_in_it_is_a_valid_file() {
    assertThat(payout(DAY).lines()).isEmpty();
    assertThat(payout(DAY).header().totalNet()).isZero();
  }

  @Test
  void missing_line_leaves_a_capture_out_for_ever() throws IOException {
    String shown = kit.cardPayment("1111", 15_000, false, "order-1");
    kit.cardPayment("1111", 15_000, false, "order-2");
    kit.simulator.planFile(FileFault.MISSING_LINE, "order-2");

    SettlementFile file = payout(DAY);

    assertThat(file.lines()).hasSize(1);
    assertThat(file.lines().get(0).gatewayPaymentId()).isEqualTo(shown);
    assertThat(payout(DAY.plusDays(1)).lines()).isEmpty();
    assertThat(kit.simulator.bankTotal()).isEqualTo(15_000 - fee(15_000));
  }

  @Test
  void extra_line_adds_a_capture_for_a_payment_that_does_not_exist() throws IOException {
    kit.cardPayment("1111", 15_000, false, "order-1");
    kit.simulator.planFile(FileFault.EXTRA_LINE, null);

    SettlementFile file = payout(DAY);
    SettlementLine ghost = file.lines().stream().filter(l -> l.gatewayPaymentId().startsWith("ghost_")).findFirst().orElseThrow();

    assertThat(file.lines()).hasSize(2);
    assertThat(ghost.gross()).isEqualTo(5_000);
    assertThat(kit.simulator.payment(ghost.gatewayPaymentId())).isNull();
    assertThat(kit.simulator.bankTotal()).isEqualTo(file.header().totalNet());
    assertThat(payout(DAY.plusDays(1)).lines()).isEmpty();
  }

  @Test
  void wrong_amount_and_short_amount_change_one_capture_line_by_one_riyal() throws IOException {
    String more = kit.cardPayment("1111", 15_000, false, "order-1");
    String less = kit.cardPayment("1111", 15_000, false, "order-2");
    kit.simulator.planFile(FileFault.WRONG_AMOUNT, "order-1");
    kit.simulator.planFile(FileFault.SHORT_AMOUNT, "order-2");

    SettlementFile file = payout(DAY);

    assertThat(lineOf(file, more, LineType.CAPTURE).gross()).isEqualTo(15_100);
    assertThat(lineOf(file, more, LineType.CAPTURE).fee()).isEqualTo(fee(15_100));
    assertThat(lineOf(file, less, LineType.CAPTURE).gross()).isEqualTo(14_900);
    assertThat(lineOf(file, less, LineType.CAPTURE).fee()).isEqualTo(fee(14_900));
  }

  @Test
  void wrong_fee_raises_one_fee_by_seven_halalas() throws IOException {
    String id = kit.cardPayment("1111", 15_000, false, "order-1");
    String other = kit.cardPayment("1111", 15_000, false, "order-2");
    kit.simulator.planFile(FileFault.WRONG_FEE, "order-1");

    SettlementFile file = payout(DAY);

    assertThat(lineOf(file, id, LineType.CAPTURE).fee()).isEqualTo(fee(15_000) + 7);
    assertThat(lineOf(file, other, LineType.CAPTURE).fee()).isEqualTo(fee(15_000));
  }

  @Test
  void duplicate_line_repeats_a_settled_capture_in_a_later_payout_with_the_same_transaction_id() throws IOException {
    String id = kit.cardPayment("1111", 15_000, false, "order-1");
    SettlementFile first = payout(DAY);
    kit.simulator.planFile(FileFault.DUPLICATE_LINE, "order-1");

    SettlementFile second = payout(DAY.plusDays(1));

    assertThat(second.lines()).hasSize(1);
    assertThat(second.lines().get(0).transactionId()).isEqualTo(lineOf(first, id, LineType.CAPTURE).transactionId());
    assertThat(kit.simulator.bankTotal()).isEqualTo(2 * (15_000 - fee(15_000)));
  }

  @Test
  void chargeback_line_takes_the_captured_amount_back_with_a_type_mizan_does_not_know() throws IOException {
    String id = kit.cardPayment("1111", 15_000, false, "order-1");
    kit.simulator.planFile(FileFault.CHARGEBACK_LINE, "order-1");

    SettlementFile file = payout(DAY);
    SettlementLine chargeback = file.lines().stream().filter(l -> l.type() == LineType.OTHER).findFirst().orElseThrow();

    assertThat(chargeback.typeText()).isEqualTo("chargeback");
    assertThat(chargeback.gatewayPaymentId()).isEqualTo(id);
    assertThat(chargeback.gross()).isEqualTo(-15_000);
  }

  @Test
  void bad_total_and_broken_file_make_a_file_that_mizan_refuses() throws IOException {
    kit.cardPayment("1111", 15_000, false, "order-1");
    kit.simulator.planFile(FileFault.BAD_TOTAL, null);
    String badTotal = kit.simulator.closePayout(Gateway.CARD_GATEWAY, DAY);
    kit.cardPayment("1111", 15_000, false, "order-2");
    kit.simulator.planFile(FileFault.BROKEN_FILE, null);
    String broken = kit.simulator.closePayout(Gateway.CARD_GATEWAY, DAY.plusDays(1));

    assertThatThrownBy(() -> SettlementFileParser.parse(new StringReader(badTotal)))
        .isInstanceOfSatisfying(SettlementFileException.class, e -> assertThat(e.code()).isEqualTo("payout_total_mismatch"));
    assertThatThrownBy(() -> SettlementFileParser.parse(new StringReader(broken))).isInstanceOf(SettlementFileException.class);
  }

  @Test
  void a_file_fault_is_used_once() throws IOException {
    kit.cardPayment("1111", 15_000, false, "order-1");
    kit.simulator.planFile(FileFault.EXTRA_LINE, null);
    payout(DAY);
    kit.cardPayment("1111", 15_000, false, "order-2");

    List<SettlementLine> next = payout(DAY.plusDays(1)).lines();

    assertThat(next).hasSize(1);
    assertThat(next.get(0).gatewayPaymentId()).doesNotStartWith("ghost_");
  }

  @Test
  void a_file_fault_of_one_order_is_used_once() throws IOException {
    kit.cardPayment("1111", 15_000, false, "order-1");
    kit.simulator.planFile(FileFault.WRONG_FEE, "order-1");
    SettlementLine first = payout(DAY).lines().get(0);
    kit.cardPayment("1111", 15_000, false, "order-1");

    SettlementLine second = payout(DAY.plusDays(1)).lines().get(0);

    assertThat(first.fee()).isNotEqualTo(fee(15_000));
    assertThat(second.fee()).isEqualTo(fee(15_000));
  }

  @Test
  void a_void_after_capture_is_settled_as_a_capture_and_a_refund() throws IOException {
    String id = kit.cardPayment("1111", 15_000, false, "order-1");
    kit.card("POST", "/payments/" + id + "/void", "{}");

    SettlementFile file = payout(DAY);

    assertThat(lineOf(file, id, LineType.CAPTURE).gross()).isEqualTo(15_000);
    assertThat(lineOf(file, id, LineType.REFUND).gross()).isEqualTo(-15_000);
  }
}
