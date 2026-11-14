package dev.mizan.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mizan.core.payment.GatewayStatus;
import dev.mizan.simulator.Kit.Answer;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Each fault of docs/lld/gateways.md that hits a call or stays on a payment. Webhook faults are in WebhookTest. */
class FaultTest {

  private final Kit kit = new Kit();

  @BeforeEach
  void be_less_patient_than_the_simulators_hang() {
    kit.patience = Duration.ofMillis(300);
  }

  @AfterEach
  void stop() {
    kit.close();
  }

  private Answer create(String id, String reference) throws IOException {
    kit.patience = Duration.ofSeconds(5);
    String token = kit.token("1111");
    kit.patience = Duration.ofMillis(300);
    return kit.card("POST", "/payments", Kit.createBody(id, 15_000, token, false, reference));
  }

  @Test
  void lost_answer_does_the_work_and_drops_the_connection() {
    String id = Kit.newId();
    kit.simulator.plan("order-1", Call.CREATE, Fault.LOST_ANSWER);

    assertThatThrownBy(() -> create(id, "order-1")).isInstanceOf(IOException.class);

    assertThat(kit.simulator.payment(id).status()).isEqualTo(GatewayStatus.PAID);
    assertThat(kit.simulator.payment(id).charges()).isEqualTo(1);
  }

  @Test
  void timeout_before_does_nothing_and_the_caller_gives_up() {
    String id = Kit.newId();
    kit.simulator.plan("order-1", Call.CREATE, Fault.TIMEOUT_BEFORE);

    assertThatThrownBy(() -> create(id, "order-1")).isInstanceOf(IOException.class);

    assertThat(kit.simulator.payment(id)).isNull();
  }

  @Test
  void slow_apply_does_the_work_a_few_seconds_after_the_caller_gave_up() {
    String id = Kit.newId();
    kit.simulator.plan("order-1", Call.CREATE, Fault.SLOW_APPLY);

    assertThatThrownBy(() -> create(id, "order-1")).isInstanceOf(IOException.class);
    assertThat(kit.simulator.payment(id)).isNull();
    kit.advance(Duration.ofSeconds(4));
    assertThat(kit.simulator.payment(id)).isNull();
    kit.advance(Duration.ofSeconds(1));

    assertThat(kit.simulator.payment(id).charges()).isEqualTo(1);
  }

  @Test
  void very_slow_apply_does_the_work_after_two_minutes() {
    String id = Kit.newId();
    kit.simulator.plan("order-1", Call.CREATE, Fault.VERY_SLOW_APPLY);

    assertThatThrownBy(() -> create(id, "order-1")).isInstanceOf(IOException.class);
    kit.advance(Duration.ofSeconds(119));
    assertThat(kit.simulator.payment(id)).isNull();
    kit.advance(Duration.ofSeconds(1));

    assertThat(kit.simulator.payment(id).status()).isEqualTo(GatewayStatus.PAID);
  }

  @Test
  void error_500_does_not_do_the_work() throws IOException {
    String id = Kit.newId();
    kit.simulator.plan("order-1", Call.CREATE, Fault.ERROR_500);

    Answer answer = create(id, "order-1");

    assertThat(answer.status()).isEqualTo(500);
    assertThat(kit.simulator.payment(id)).isNull();
  }

  @Test
  void error_500_after_does_the_work_and_still_answers_500() throws IOException {
    String id = Kit.newId();
    kit.simulator.plan("order-1", Call.CREATE, Fault.ERROR_500_AFTER);

    Answer answer = create(id, "order-1");

    assertThat(answer.status()).isEqualTo(500);
    assertThat(kit.simulator.payment(id).charges()).isEqualTo(1);
  }

  @Test
  void garbage_does_the_work_and_answers_200_with_something_that_is_not_the_record() throws IOException {
    String id = Kit.newId();
    kit.simulator.plan("order-1", Call.CREATE, Fault.GARBAGE);

    Answer answer = create(id, "order-1");

    assertThat(answer.status()).isEqualTo(200);
    assertThat(answer.text()).startsWith("<html>");
    assertThat(kit.simulator.payment(id).charges()).isEqualTo(1);
  }

  @Test
  void a_planned_fault_hits_one_call_of_its_order_and_its_kind() throws IOException {
    kit.simulator.plan("order-1", Call.CREATE, Fault.ERROR_500);

    assertThat(create(Kit.newId(), "order-2").status()).isEqualTo(201);
    assertThat(create(Kit.newId(), "order-1").status()).isEqualTo(500);
    assertThat(create(Kit.newId(), "order-1").status()).isEqualTo(201);
  }

  @Test
  void faults_hit_the_other_calls_too() throws IOException {
    kit.patience = Duration.ofSeconds(5);
    String id = kit.cardPayment("1111", 15_000, true, "order-1");
    kit.simulator.plan("order-1", Call.FETCH, Fault.ERROR_500);
    kit.simulator.plan("order-1", Call.CAPTURE, Fault.ERROR_500_AFTER);
    kit.simulator.plan("order-1", Call.REFUND, Fault.ERROR_500);
    kit.simulator.plan("order-1", Call.VOID, Fault.ERROR_500);

    assertThat(kit.card("GET", "/payments/" + id, null).status()).isEqualTo(500);
    assertThat(kit.card("GET", "/payments/" + id, null).status()).isEqualTo(200);
    assertThat(kit.card("POST", "/payments/" + id + "/capture", "{}").status()).isEqualTo(500);
    assertThat(kit.simulator.payment(id).captured()).isEqualTo(15_000);
    assertThat(kit.card("POST", "/payments/" + id + "/refund", "{\"amount\":100}").status()).isEqualTo(500);
    assertThat(kit.simulator.payment(id).refunded()).isZero();
    assertThat(kit.card("POST", "/payments/" + id + "/void", "{}").status()).isEqualTo(500);
  }

  @Test
  void a_slow_refund_lands_after_the_caller_gave_up() throws IOException {
    kit.patience = Duration.ofSeconds(5);
    String id = kit.cardPayment("1111", 15_000, false, "order-1");
    kit.patience = Duration.ofMillis(300);
    kit.simulator.plan("order-1", Call.REFUND, Fault.SLOW_APPLY);

    assertThatThrownBy(() -> kit.card("POST", "/payments/" + id + "/refund", "{\"amount\":5000}")).isInstanceOf(IOException.class);
    assertThat(kit.simulator.payment(id).refunded()).isZero();
    kit.advance(Duration.ofSeconds(5));

    assertThat(kit.simulator.payment(id).refunded()).isEqualTo(5_000);
  }

  @Test
  void fetch_down_answers_503_for_a_while() throws IOException {
    kit.patience = Duration.ofSeconds(5);
    String id = kit.cardPayment("1111", 15_000, false, "order-1");
    kit.simulator.fetchDown("order-1", 2);

    assertThat(kit.card("GET", "/payments/" + id, null).status()).isEqualTo(503);
    assertThat(kit.card("GET", "/payments/" + id, null).status()).isEqualTo(503);
    assertThat(kit.card("GET", "/payments/" + id, null).status()).isEqualTo(200);
  }

  @Test
  void amount_changed_shows_another_amount_on_a_fetch() throws IOException {
    kit.patience = Duration.ofSeconds(5);
    String id = kit.cardPayment("1111", 15_000, false, "order-1");
    String payLater = kit.payLaterPayment("150.00", "order-1");
    kit.simulator.stick("order-1", Fault.AMOUNT_CHANGED);

    assertThat(kit.card("GET", "/payments/" + id, null).json().path("amount").asLong()).isEqualTo(15_100);
    assertThat(kit.payLater("GET", "/payments/" + payLater, null).json().path("amount").asString()).isEqualTo("151.00");
    assertThat(kit.simulator.payment(id).amount).isEqualTo(15_000);
  }

  @Test
  void hold_released_keeps_the_record_authorized_and_refuses_the_capture() throws IOException {
    kit.patience = Duration.ofSeconds(5);
    String card = kit.cardPayment("1111", 15_000, true, "order-1");
    String payLater = kit.payLaterPayment("150.00", "order-1");
    kit.simulator.customerApproves(payLater);
    kit.simulator.stick("order-1", Fault.HOLD_RELEASED);

    Answer cardCapture = kit.card("POST", "/payments/" + card + "/capture", "{}");
    Answer payLaterCapture = kit.payLater("POST", "/payments/" + payLater + "/captures", "{\"amount\":\"150.00\"}");

    assertThat(cardCapture.status()).isEqualTo(400);
    assertThat(cardCapture.json().path("type").asString()).isEqualTo("authorization_expired");
    assertThat(payLaterCapture.status()).isEqualTo(400);
    assertThat(kit.card("GET", "/payments/" + card, null).json().path("status").asString()).isEqualTo("authorized");
    assertThat(kit.simulator.payment(card).charges()).isZero();
  }

  @Test
  void a_refund_made_outside_the_api_is_in_the_record() throws IOException {
    kit.patience = Duration.ofSeconds(5);
    String id = kit.cardPayment("1111", 15_000, false, "order-1");

    kit.simulator.externalRefund(id, 4_000);

    assertThat(kit.card("GET", "/payments/" + id, null).json().path("refunded").asLong()).isEqualTo(4_000);
  }

  @Test
  void sticky_and_call_faults_are_planned_in_their_own_way() {
    assertThatThrownBy(() -> kit.simulator.plan("o", Call.CREATE, Fault.WEBHOOK_DROP)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> kit.simulator.stick("o", Fault.LOST_ANSWER)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void the_control_calls_plan_faults_move_the_clock_and_act_for_the_customer() throws IOException {
    kit.patience = Duration.ofSeconds(5);
    String id = kit.cardPayment("3333", 15_000, false, "order-1");

    Answer fault = kit.send("POST", "/_sim/fault", "{\"reference\":\"order-1\",\"call\":\"FETCH\",\"fault\":\"ERROR_500\"}", null);
    Answer sticky = kit.send("POST", "/_sim/fault", "{\"reference\":\"order-1\",\"fault\":\"AMOUNT_CHANGED\"}", null);
    Answer customer = kit.send("POST", "/_sim/customer", "{\"id\":\"" + id + "\",\"action\":\"approve\"}", null);
    Answer clock = kit.send("POST", "/_sim/clock", "{\"advance_seconds\":90}", null);
    Answer refund = kit.send("POST", "/_sim/external-refund", "{\"id\":\"" + id + "\",\"amount\":500}", null);
    Answer unknown = kit.send("POST", "/_sim/nothing", "{}", null);
    Answer wrong = kit.send("POST", "/_sim/customer", "{\"id\":\"" + id + "\",\"action\":\"dance\"}", null);

    assertThat(fault.status()).isEqualTo(200);
    assertThat(sticky.status()).isEqualTo(200);
    assertThat(customer.json().path("redirect").asString()).contains("status=paid");
    assertThat(clock.status()).isEqualTo(200);
    assertThat(kit.clock.instant()).isEqualTo(Kit.START.plusSeconds(90));
    assertThat(refund.status()).isEqualTo(200);
    assertThat(unknown.status()).isEqualTo(404);
    assertThat(wrong.status()).isEqualTo(400);
    assertThat(kit.card("GET", "/payments/" + id, null).status()).isEqualTo(500);
    assertThat(kit.card("GET", "/payments/" + id, null).json().path("amount").asLong()).isEqualTo(15_100);
    assertThat(kit.simulator.payment(id).refunded()).isEqualTo(500);
    assertThat(kit.send("GET", "/health", null, null).text()).isEqualTo("ok");
  }
}
