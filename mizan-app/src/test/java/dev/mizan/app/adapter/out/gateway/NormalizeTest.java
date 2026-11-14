package dev.mizan.app.adapter.out.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mizan.core.payment.GatewayStatus;
import dev.mizan.core.sync.Snapshot;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Each gateway's words in Mizan's one shape, row by row. See docs/lld/gateways.md, "Normalizing". */
class NormalizeTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static JsonNode json(String text) {
    return JSON.readTree(text.replace('\'', '"'));
  }

  @Nested
  class Card_gateway {

    private Snapshot card(String fields) {
      return CardGatewayClient.normalize(json("{'id':'p1','amount':15000,'currency':'SAR'," + fields + "}"), "p1");
    }

    @Test
    void initiated_is_initiated_with_the_bank_pages_address() {
      Snapshot snapshot = card("'status':'initiated','source':{'transaction_url':'https://bank.example/3ds','company':'mada','number':'XXXX-XXXX-XXXX-3333'}");

      assertThat(snapshot.status()).isEqualTo(GatewayStatus.INITIATED);
      assertThat(snapshot.actionUrl()).isEqualTo("https://bank.example/3ds");
      assertThat(snapshot.scheme()).isEqualTo("mada");
      assertThat(snapshot.last4()).isEqualTo("3333");
      assertThat(snapshot.amount()).isEqualTo(15_000);
      assertThat(snapshot.currency()).isEqualTo("SAR");
    }

    @Test
    void authorized_is_authorized_with_nothing_captured() {
      Snapshot snapshot = card("'status':'authorized'");

      assertThat(snapshot.status()).isEqualTo(GatewayStatus.AUTHORIZED);
      assertThat(snapshot.captured()).isZero();
    }

    @Test
    void paid_is_paid_with_the_whole_amount_captured() {
      Snapshot snapshot = card("'status':'paid','captured':15000,'refunded':0");

      assertThat(snapshot).isEqualTo(Snapshot.paid(15_000, "SAR", 15_000, 0));
      assertThat(card("'status':'paid'")).isEqualTo(Snapshot.paid(15_000, "SAR", 15_000, 0)); // also without the totals
    }

    @Test
    void captured_is_paid_with_the_captured_amount_as_given() {
      assertThat(card("'status':'captured','captured':10000,'refunded':0")).isEqualTo(Snapshot.paid(15_000, "SAR", 10_000, 0));
    }

    @Test
    void refunded_is_paid_with_the_refunded_amount_as_given() {
      assertThat(card("'status':'refunded','captured':15000,'refunded':5000")).isEqualTo(Snapshot.paid(15_000, "SAR", 15_000, 5_000));
    }

    @Test
    void voided_with_nothing_captured_is_voided() {
      assertThat(card("'status':'voided','captured':0").status()).isEqualTo(GatewayStatus.VOIDED);
      assertThat(card("'status':'voided'").status()).isEqualTo(GatewayStatus.VOIDED);
    }

    @Test
    void voided_after_capture_is_paid_and_fully_refunded() {
      assertThat(card("'status':'voided','captured':15000,'refunded':0")).isEqualTo(Snapshot.paid(15_000, "SAR", 15_000, 15_000));
    }

    @Test
    void failed_is_failed_with_the_gateways_message() {
      Snapshot snapshot = card("'status':'failed','source':{'message':'INSUFFICIENT FUNDS'}");

      assertThat(snapshot.status()).isEqualTo(GatewayStatus.FAILED);
      assertThat(snapshot.failureCode()).isEqualTo("declined");
      assertThat(snapshot.failureMessage()).isEqualTo("INSUFFICIENT FUNDS");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "'status':'on_hold'",                                   // a status Mizan does not know
        "'status':'captured','captured':15000.5",               // not whole halalas
        "'status':'captured','captured':'15000'",               // text where a number belongs
        "'status':'authorized','captured':15000",               // money in a status without money
        "'status':'initiated','captured':15000",
        "'status':'failed','captured':15000",
        "'status':'refunded','captured':5000,'refunded':6000",  // more refunded than captured
        "'status':'captured','captured':20000",                 // more captured than the amount
        "'status':'captured','captured':0",                     // captured, with nothing captured
    })
    void a_record_that_cannot_be_true_is_not_read(String fields) {
      assertThatThrownBy(() -> card(fields)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void a_record_of_another_payment_or_without_amount_or_currency_is_not_read() {
      assertThatThrownBy(() -> CardGatewayClient.normalize(json("{'id':'p2','status':'paid','amount':15000,'currency':'SAR'}"), "p1"))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(() -> CardGatewayClient.normalize(json("{'id':'p1','status':'paid','currency':'SAR'}"), "p1"))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(() -> CardGatewayClient.normalize(json("{'id':'p1','status':'authorized','amount':15000}"), "p1"))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(() -> CardGatewayClient.normalize(null, "p1")).isInstanceOf(RuntimeException.class);
    }
  }

  @Nested
  class Pay_later {

    private Snapshot payLater(String fields) {
      return PayLaterClient.normalize(json("{'id':'p1','amount':'150.00','currency':'SAR'," + fields + "}"), "p1");
    }

    @Test
    void created_is_initiated() {
      Snapshot snapshot = payLater("'status':'CREATED','captures':[],'refunds':[]");

      assertThat(snapshot.status()).isEqualTo(GatewayStatus.INITIATED);
      assertThat(snapshot.amount()).isEqualTo(15_000); // riyals as text become halalas, exactly
    }

    @Test
    void authorized_is_authorized() {
      assertThat(payLater("'status':'AUTHORIZED'").status()).isEqualTo(GatewayStatus.AUTHORIZED);
    }

    @Test
    void closed_with_a_capture_is_paid_with_the_sums_of_captures_and_refunds() {
      Snapshot snapshot = payLater("'status':'CLOSED','captures':[{'amount':'150.00'}],'refunds':[{'amount':'20.00'},{'amount':'30.50'}]");

      assertThat(snapshot).isEqualTo(Snapshot.paid(15_000, "SAR", 15_000, 5_050));
    }

    @Test
    void still_authorized_after_a_part_capture_made_at_the_provider_is_paid_for_that_part() {
      Snapshot snapshot = payLater("'status':'AUTHORIZED','captures':[{'amount':'100.00'}]");

      assertThat(snapshot).isEqualTo(Snapshot.paid(15_000, "SAR", 10_000, 0));
    }

    @Test
    void closed_with_nothing_captured_is_voided() {
      assertThat(payLater("'status':'CLOSED','captures':[]").status()).isEqualTo(GatewayStatus.VOIDED);
    }

    @Test
    void rejected_is_failed_and_expired_is_expired() {
      assertThat(payLater("'status':'REJECTED'").status()).isEqualTo(GatewayStatus.FAILED);
      assertThat(payLater("'status':'REJECTED'").failureCode()).isEqualTo("declined");
      assertThat(payLater("'status':'EXPIRED'").status()).isEqualTo(GatewayStatus.EXPIRED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "'status':'NEW'",                                                       // a status Mizan does not know
        "'status':'created'",                                                   // the provider writes statuses in capitals here
        "'status':'CLOSED','captures':[{'amount':'150.005'}]",                  // three decimals
        "'status':'CLOSED','captures':[{'amount':'200.00'}]",                   // more captured than the amount
        "'status':'CLOSED','captures':[{'amount':'10.00'}],'refunds':[{'amount':'20.00'}]", // more refunded than captured
        "'status':'CLOSED','captures':[{'amount':150}]",                        // a number where text belongs
        "'status':'CREATED','captures':[{'amount':'150.00'}]",                  // money in a status without money
        "'status':'REJECTED','captures':[{'amount':'150.00'}]",
        "'status':'EXPIRED','captures':[{'amount':'150.00'}]",
    })
    void a_record_that_cannot_be_true_is_not_read(String fields) {
      assertThatThrownBy(() -> payLater(fields)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void a_record_of_another_payment_or_with_an_amount_that_is_not_riyals_is_not_read() {
      assertThatThrownBy(() -> PayLaterClient.normalize(json("{'id':'p2','status':'CREATED','amount':'150.00','currency':'SAR'}"), "p1"))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(() -> PayLaterClient.normalize(json("{'id':'p1','status':'CREATED','amount':15000,'currency':'SAR'}"), "p1"))
          .isInstanceOf(RuntimeException.class);
    }
  }
}
