package dev.mizan.app.adapter.out.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.app.application.port.GatewayClient;
import dev.mizan.app.application.port.GatewayClient.Create;
import dev.mizan.app.application.port.GatewayResult;
import dev.mizan.app.application.port.GatewayResult.Done;
import dev.mizan.app.application.port.GatewayResult.Refused;
import dev.mizan.app.application.port.GatewayResult.Unknown;
import dev.mizan.core.payment.Gateway;
import dev.mizan.core.payment.GatewayStatus;
import dev.mizan.core.payment.Method;
import dev.mizan.core.sync.Snapshot;
import dev.mizan.simulator.Call;
import dev.mizan.simulator.Fault;
import dev.mizan.simulator.MutableClock;
import dev.mizan.simulator.Simulator;
import dev.mizan.simulator.SimulatorServer;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The two gateway clients against the simulator: each call, and each of the three results. No database is needed.
 * See docs/lld/gateways.md.
 */
class GatewayClientsTest {

  private static final Simulator SIMULATOR = new Simulator(
      new MutableClock(Instant.parse("2026-11-14T09:00:00Z")), Simulator.Config.defaults().withHang(Duration.ofMillis(700)));
  private static final SimulatorServer SERVER = SimulatorServer.start(SIMULATOR, 0);
  private static final HttpGateways GATEWAYS = gateways(SIMULATOR.config().cardKey(), SIMULATOR.config().payLaterKey());

  private final GatewayClient card = GATEWAYS.of(Gateway.CARD_GATEWAY);
  private final GatewayClient payLater = GATEWAYS.of(Gateway.PAY_LATER);
  private final String order = "order-" + UUID.randomUUID();

  @AfterAll
  static void stop() {
    SERVER.close();
  }

  private static HttpGateways gateways(String cardKey, String payLaterKey) {
    return new HttpGateways(
        new HttpGateways.Access(SERVER.baseUrl() + "/card/v1", cardKey),
        new HttpGateways.Access(SERVER.baseUrl() + "/paylater/v2/", payLaterKey), // a slash at the end is fine
        Duration.ofSeconds(2), Duration.ofMillis(300));
  }

  private Create create(Method method, String last4, boolean manual) {
    String token = method == Method.PAY_LATER ? null : SIMULATOR.createToken("588845000000" + last4, "mada", method == Method.APPLE_PAY);
    return new Create(UUID.randomUUID(), order, 15_000, "SAR", method, token, manual, "https://mizan.example/v1/return/x", "Order 1");
  }

  private static Snapshot snapshot(GatewayResult result) {
    assertThat(result).isInstanceOf(Done.class);
    return ((Done) result).snapshot();
  }

  @Nested
  class Card_gateway {

    @Test
    void a_create_carries_mizans_id_and_gives_the_record() {
      Create request = create(Method.CARD, "1111", false);

      GatewayResult result = card.create(request);

      assertThat(((Done) result).gatewayPaymentId()).isEqualTo(request.paymentId().toString());
      assertThat(snapshot(result)).isEqualTo(Snapshot.paid(15_000, "SAR", 15_000, 0).withCard("mada", "1111"));
      var atGateway = SIMULATOR.payment(request.paymentId().toString());
      assertThat(atGateway.reference).isEqualTo(order);
      assertThat(atGateway.mizanPaymentId).isEqualTo(request.paymentId().toString());
      assertThat(atGateway.callbackUrl).isEqualTo("https://mizan.example/v1/return/x");
    }

    @Test
    void a_manual_create_holds_the_money() {
      Create request = create(Method.CARD, "1111", true);

      assertThat(snapshot(card.create(request)).status()).isEqualTo(GatewayStatus.AUTHORIZED);
      assertThat(SIMULATOR.payment(request.paymentId().toString()).holds()).isEqualTo(1);
      assertThat(SIMULATOR.payment(request.paymentId().toString()).charges()).isZero();
    }

    @Test
    void a_card_that_needs_the_banks_code_is_initiated_with_the_page_address() {
      Snapshot snapshot = snapshot(card.create(create(Method.CARD, "3333", false)));

      assertThat(snapshot.status()).isEqualTo(GatewayStatus.INITIATED);
      assertThat(snapshot.actionUrl()).startsWith(SERVER.baseUrl() + "/card/3ds/");
    }

    @Test
    void a_declined_card_is_done_with_a_failed_record_not_refused() {
      Snapshot snapshot = snapshot(card.create(create(Method.CARD, "0002", false)));

      assertThat(snapshot.status()).isEqualTo(GatewayStatus.FAILED);
      assertThat(snapshot.failureCode()).isEqualTo("declined");
      assertThat(snapshot.failureMessage()).isEqualTo("INSUFFICIENT FUNDS");
    }

    @Test
    void a_wallet_token_pays_like_a_card() {
      assertThat(snapshot(card.create(create(Method.APPLE_PAY, "1111", false))).status()).isEqualTo(GatewayStatus.PAID);
    }

    @Test
    void a_token_that_is_not_known_is_refused() {
      Create request = new Create(UUID.randomUUID(), order, 15_000, "SAR", Method.CARD, "tok_unknown", false, "https://mizan.example/r", null);

      GatewayResult result = card.create(request);

      assertThat(result).isInstanceOf(Refused.class);
      assertThat(((Refused) result).code()).isEqualTo("gateway_refused");
      assertThat(((Refused) result).message()).isNotBlank();
      assertThat(SIMULATOR.payment(request.paymentId().toString())).isNull();
    }

    @Test
    void a_fetch_gives_the_record_and_not_found_for_a_payment_the_gateway_does_not_have() {
      Create request = create(Method.CARD, "1111", false);
      card.create(request);

      assertThat(snapshot(card.fetch(request.paymentId().toString())).captured()).isEqualTo(15_000);
      assertThat(snapshot(card.fetch(UUID.randomUUID().toString())).status()).isEqualTo(GatewayStatus.NOT_FOUND);
    }

    @Test
    void capture_void_and_refund_give_the_record_after_the_work() {
      String held = create(card, create(Method.CARD, "1111", true));
      String voided = create(card, create(Method.CARD, "1111", true));

      assertThat(snapshot(card.capture(held, 10_000))).isEqualTo(Snapshot.paid(15_000, "SAR", 10_000, 0).withCard("mada", "1111"));
      assertThat(snapshot(card.refund(held, 4_000, "ignored")).refunded()).isEqualTo(4_000);
      assertThat(snapshot(card.cancel(voided)).status()).isEqualTo(GatewayStatus.VOIDED);
    }

    @Test
    void a_void_after_capture_reads_as_paid_and_fully_refunded() {
      String paid = create(card, create(Method.CARD, "1111", false));

      assertThat(snapshot(card.cancel(paid))).isEqualTo(Snapshot.paid(15_000, "SAR", 15_000, 15_000).withCard("mada", "1111"));
    }

    @Test
    void a_refund_above_what_is_left_and_a_capture_of_a_paid_payment_are_refused() {
      String paid = create(card, create(Method.CARD, "1111", false));

      assertThat(card.refund(paid, 15_001, "k")).isInstanceOf(Refused.class);
      assertThat(card.capture(paid, 15_000)).isInstanceOf(Refused.class);
      assertThat(card.capture(UUID.randomUUID().toString(), 100)).isInstanceOf(Refused.class); // no such payment
      assertThat(SIMULATOR.payment(paid).refunded()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = Fault.class, names = {"LOST_ANSWER", "TIMEOUT_BEFORE", "SLOW_APPLY", "ERROR_500", "ERROR_500_AFTER", "GARBAGE"})
    void no_usable_answer_is_unknown_whatever_really_happened(Fault fault) {
      SIMULATOR.plan(order, Call.CREATE, fault);

      assertThat(card.create(create(Method.CARD, "1111", false))).isInstanceOf(Unknown.class);
    }

    @Test
    void a_fetch_that_fails_is_unknown_not_not_found() {
      String paid = create(card, create(Method.CARD, "1111", false));
      SIMULATOR.fetchDown(order, 1);

      assertThat(card.fetch(paid)).isInstanceOf(Unknown.class);
      assertThat(card.fetch(paid)).isInstanceOf(Done.class);
    }

    @Test
    void a_wrong_key_is_unknown_because_it_says_nothing_about_the_payment() {
      GatewayClient wrong = gateways("sk_wrong", "pl_wrong").of(Gateway.CARD_GATEWAY);

      assertThat(wrong.create(create(Method.CARD, "1111", false))).isEqualTo(new Unknown("http_401"));
    }

    @Test
    void a_record_with_another_amount_is_still_read_so_that_sync_can_refuse_it() {
      String paid = create(card, create(Method.CARD, "1111", false));
      SIMULATOR.stick(order, Fault.AMOUNT_CHANGED);

      assertThat(snapshot(card.fetch(paid)).amount()).isEqualTo(15_100);
    }
  }

  @Nested
  class Pay_later {

    @Test
    void a_create_gives_the_providers_id_and_the_page_address() {
      Create request = create(Method.PAY_LATER, null, false);

      GatewayResult result = payLater.create(request);

      String id = ((Done) result).gatewayPaymentId();
      assertThat(snapshot(result).status()).isEqualTo(GatewayStatus.INITIATED);
      assertThat(snapshot(result).amount()).isEqualTo(15_000);
      assertThat(snapshot(result).actionUrl()).isEqualTo(SERVER.baseUrl() + "/paylater/checkout/" + id);
      assertThat(id).isNotEqualTo(request.paymentId().toString());
      assertThat(SIMULATOR.payment(id).mizanPaymentId).isEqualTo(request.paymentId().toString());
      assertThat(SIMULATOR.payment(id).reference).isEqualTo(order);
    }

    @Test
    void the_whole_life_of_a_payment() {
      String id = create(payLater, create(Method.PAY_LATER, null, false));

      SIMULATOR.customerApproves(id);
      assertThat(snapshot(payLater.fetch(id)).status()).isEqualTo(GatewayStatus.AUTHORIZED);
      assertThat(snapshot(payLater.capture(id, 15_000))).isEqualTo(Snapshot.paid(15_000, "SAR", 15_000, 0));
      assertThat(snapshot(payLater.refund(id, 5_050, "refund-1")).refunded()).isEqualTo(5_050);
    }

    @Test
    void the_same_refund_key_twice_refunds_once() {
      String id = create(payLater, create(Method.PAY_LATER, null, false));
      SIMULATOR.customerApproves(id);
      payLater.capture(id, 15_000);

      payLater.refund(id, 5_000, "refund-1");
      Snapshot again = snapshot(payLater.refund(id, 5_000, "refund-1"));

      assertThat(again.refunded()).isEqualTo(5_000);
      assertThat(SIMULATOR.payment(id).refunded()).isEqualTo(5_000);
    }

    @Test
    void a_hold_that_is_closed_reads_as_voided_and_a_rejected_one_as_failed() {
      String closed = create(payLater, create(Method.PAY_LATER, null, false));
      String rejected = create(payLater, create(Method.PAY_LATER, null, false));
      String left = create(payLater, create(Method.PAY_LATER, null, false));
      SIMULATOR.customerApproves(closed);
      SIMULATOR.customerDeclines(rejected);
      SIMULATOR.customerLeaves(left);

      assertThat(snapshot(payLater.cancel(closed)).status()).isEqualTo(GatewayStatus.VOIDED);
      assertThat(snapshot(payLater.fetch(rejected)).status()).isEqualTo(GatewayStatus.FAILED);
      assertThat(snapshot(payLater.fetch(left)).status()).isEqualTo(GatewayStatus.EXPIRED);
      assertThat(snapshot(payLater.fetch("nothing")).status()).isEqualTo(GatewayStatus.NOT_FOUND);
    }

    @Test
    void a_part_capture_and_a_capture_before_approval_are_refused() {
      String id = create(payLater, create(Method.PAY_LATER, null, false));

      assertThat(payLater.capture(id, 15_000)).isInstanceOf(Refused.class);
      SIMULATOR.customerApproves(id);
      assertThat(payLater.capture(id, 10_000)).isInstanceOf(Refused.class);
    }

    @ParameterizedTest
    @EnumSource(value = Fault.class, names = {"LOST_ANSWER", "TIMEOUT_BEFORE", "ERROR_500", "GARBAGE"})
    void no_usable_answer_is_unknown(Fault fault) {
      SIMULATOR.plan(order, Call.CREATE, fault);

      assertThat(payLater.create(create(Method.PAY_LATER, null, false))).isInstanceOf(Unknown.class);
    }

    @Test
    void a_wrong_key_is_unknown() {
      GatewayClient wrong = gateways("sk_wrong", "pl_wrong").of(Gateway.PAY_LATER);

      assertThat(wrong.fetch("anything")).isEqualTo(new Unknown("http_401"));
    }
  }

  /** Creates the payment and returns its id at the gateway. */
  private static String create(GatewayClient gateway, Create request) {
    return ((Done) gateway.create(request)).gatewayPaymentId();
  }
}
