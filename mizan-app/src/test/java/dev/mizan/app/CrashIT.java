package dev.mizan.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.app.application.OperationRunner;
import dev.mizan.app.application.model.OperationRecord;
import dev.mizan.app.application.model.Origin;
import dev.mizan.app.application.model.PaymentRecord;
import dev.mizan.app.application.port.Operations;
import dev.mizan.app.application.port.Payments;
import dev.mizan.app.application.port.Transactions;
import dev.mizan.app.support.IntegrationTest;
import dev.mizan.core.payment.CaptureMode;
import dev.mizan.core.payment.Method;
import dev.mizan.core.payment.OperationType;
import dev.mizan.core.payment.Payment;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The process dies in the middle of an operation. Every operation is written down as in flight, with its call
 * counted, before the call is sent. So whatever the moment of the crash, the sync worker finds the operation and
 * finishes it. Each test here does by hand the steps that ran before the crash, and no more.
 * See docs/lld/payments.md, "The pattern of every command".
 */
class CrashIT extends IntegrationTest {

  private static final Duration QUIET = Duration.ofMinutes(1);

  private final Payments payments = world.app.getBean(Payments.class);
  private final Operations operations = world.app.getBean(Operations.class);
  private final OperationRunner runner = world.app.getBean(OperationRunner.class);
  private final Transactions transactions = world.app.getBean(Transactions.class);

  /** Transaction 1 of a create, as the service runs it: the payment and its create in flight. Then the process dies. */
  private String createCommitted(String order, Method method) {
    UUID id = UUID.randomUUID();
    transactions.run(() -> {
      payments.lockOrder(order);
      Payment fresh = Payment.pending(15_000, "SAR", method.gateway(), CaptureMode.AUTO, now().plus(Duration.ofMinutes(30)));
      String gatewayId = method.gateway().callerChoosesId() ? id.toString() : null;
      var payment = new PaymentRecord(
          id, order, method, null, "https://store.example/orders/1", gatewayId, fresh, 0, 0, null, false, null, 0, 1, now(), now());
      payments.insert(payment);
      runner.start(payment, UUID.randomUUID(), OperationType.CREATE, Origin.API, 15_000, null, now());
    });
    return id.toString();
  }

  /** Transaction 1 of a capture or a refund: the operation in flight. Then the process dies. */
  private OperationRecord operationCommitted(String paymentId, OperationType type, long amount) {
    return transactions.run(() -> {
      PaymentRecord locked = payments.lock(UUID.fromString(paymentId)).orElseThrow();
      return runner.start(locked, UUID.randomUUID(), type, Origin.API, amount, null, now());
    });
  }

  @Test
  void an_operation_in_flight_always_has_a_time_for_the_worker_to_look() {
    String id = createCommitted(newOrder(), Method.CARD);

    // Written in transaction 1 itself. If only a later step set it, a crash here would leave the payment waiting for ever.
    assertThat(time(id, "next_sync_at")).isEqualTo(now().plus(QUIET));
    assertThat(operations(id)).containsExactly("CREATE IN_FLIGHT API 15000");
  }

  @Test
  void a_crash_before_the_create_was_sent_ends_as_a_failed_payment() {
    String order = newOrder();
    String id = createCommitted(order, Method.CARD);

    advance(QUIET.minusSeconds(1));
    round();
    assertThat(payment(id).text("status")).isEqualTo("PENDING");
    advance(Duration.ofSeconds(1));
    round();

    assertThat(payment(id).text("status")).isEqualTo("FAILED");
    assertThat(payment(id).json().path("failure").path("code").asString()).isEqualTo("gateway_unreachable");
    assertThat(events(id)).containsExactly("payment.failed");
    assertThat(world.simulator.paymentsOf(order)).isEmpty(); // nothing was sent, then or later
  }

  @Test
  void a_crash_after_the_create_reached_the_gateway_is_found_by_the_worker() {
    String order = newOrder();
    String id = createCommitted(order, Method.CARD);
    // The call left Mizan and the card was charged. Then the process died, before the answer was written down.
    world.simulator.cardCreate(id, 15_000, "SAR", token("1111"), false, "https://mizan.example/v1/return/" + id, order, id);

    advance(QUIET);
    round();

    assertThat(payment(id).text("status")).isEqualTo("CAPTURED");
    assertThat(atGateway(id).charges()).isEqualTo(1);
    assertThat(entries(id)).containsExactly("CAPTURE 15000");
    assertThat(events(id)).containsExactly("payment.captured");
    assertThat(operations(id)).containsExactly("CREATE SUCCEEDED API 15000");
  }

  @Test
  void a_crash_before_a_pay_later_create_was_answered_ends_as_a_failed_payment() {
    String id = createCommitted(newOrder(), Method.PAY_LATER); // no id at the provider: nothing to ask about

    advance(QUIET.minusSeconds(1));
    assertThat(sync(id).text("status")).isEqualTo("PENDING"); // a step now decides nothing: the call may be on its way
    assertThat(time(id, "next_sync_at")).isEqualTo(now().plusSeconds(1));
    advance(Duration.ofSeconds(1));
    round();

    assertThat(payment(id).text("status")).isEqualTo("FAILED");
    assertThat(payment(id).json().path("failure").path("code").asString()).isEqualTo("gateway_unreachable");
    assertThat(operations(id)).containsExactly("CREATE FAILED API 15000");
    assertThat(column(id, "next_sync_at")).isNull();
  }

  @Test
  void a_crash_before_a_capture_was_sent_is_finished_by_sending_it() {
    String id = authorized(newOrder(), 15_000);
    operationCommitted(id, OperationType.CAPTURE, 15_000);

    advance(QUIET);
    round(); // nothing changed at the gateway, and a capture is safe to send again

    assertThat(payment(id).text("status")).isEqualTo("CAPTURED");
    assertThat(atGateway(id).captured()).isEqualTo(15_000);
    assertThat(operations(id)).containsExactly("CREATE SUCCEEDED API 15000", "CAPTURE SUCCEEDED API 15000");
  }

  @Test
  void a_crash_after_a_capture_reached_the_gateway_is_found_without_a_second_capture() {
    String id = authorized(newOrder(), 15_000);
    OperationRecord capture = operationCommitted(id, OperationType.CAPTURE, 15_000);
    world.simulator.cardCapture(id, 15_000L); // the call landed; the process died before it heard

    advance(QUIET);
    round();

    assertThat(payment(id).text("status")).isEqualTo("CAPTURED");
    assertThat(operations.find(capture.id()).orElseThrow().attempts()).isEqualTo(1); // found by looking, not by sending again
    assertThat(atGateway(id).charges()).isEqualTo(1);
  }

  @Test
  void a_crash_before_a_card_refund_was_sent_ends_as_a_failed_refund_never_as_a_double_one() {
    String id = captured(newOrder(), 15_000);
    operationCommitted(id, OperationType.REFUND, 5_000);

    advance(QUIET);
    round();

    // Mizan cannot know that the call never left. So it does not send it "again": that could refund twice.
    assertThat(operations(id)).containsExactly("CREATE SUCCEEDED API 15000", "REFUND FAILED API 5000");
    assertThat(events(id)).containsExactly("payment.captured", "refund.failed");
    assertThat(atGateway(id).refunded()).isZero();
  }

  @Test
  void a_crash_after_the_second_call_was_written_down_but_not_sent_ends_without_effect() {
    String id = authorized(newOrder(), 15_000);
    OperationRecord capture = operationCommitted(id, OperationType.CAPTURE, 15_000);
    advance(QUIET);
    transactions.run(() -> operations.attempt(capture.id(), 2, now())); // the worker wrote "sending again", then died

    advance(QUIET);
    round();

    // Two calls are counted, so no third is sent. The hold is still there, and the store is told.
    assertThat(payment(id).text("status")).isEqualTo("AUTHORIZED");
    assertThat(operations(id)).containsExactly("CREATE SUCCEEDED API 15000", "CAPTURE FAILED API 15000");
    assertThat(events(id)).containsExactly("payment.authorized", "capture.failed");
    assertThat(atGateway(id).captured()).isZero();
  }
}
