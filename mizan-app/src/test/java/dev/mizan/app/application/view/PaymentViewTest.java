package dev.mizan.app.application.view;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.app.application.model.OperationRecord;
import dev.mizan.app.application.model.Origin;
import dev.mizan.app.application.model.PaymentRecord;
import dev.mizan.core.payment.CaptureMode;
import dev.mizan.core.payment.Gateway;
import dev.mizan.core.payment.Method;
import dev.mizan.core.payment.OperationType;
import dev.mizan.core.payment.Payment;
import dev.mizan.core.payment.PaymentStatus;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** What the API shows of a payment, and above all why a pending payment is pending. */
class PaymentViewTest {

  private static final Instant NOW = Instant.parse("2026-11-14T09:00:00Z");
  private static final UUID ID = UUID.randomUUID();

  private static PaymentRecord record(Consumer<Payment.Draft> change) {
    Payment payment = Payment.pending(15_000, "SAR", Gateway.CARD_GATEWAY, CaptureMode.AUTO, NOW.plusSeconds(1_800)).with(change);
    return new PaymentRecord(ID, "order-1", Method.CARD, "Order 1", "https://store.example/o/1", ID.toString(), payment, 0, 0, null, false, null, 0, 3, NOW, NOW);
  }

  private static OperationRecord inFlight(OperationType type) {
    return OperationRecord.inFlight(UUID.randomUUID(), ID, type, Origin.API, 15_000, null, NOW);
  }

  @Test
  void a_payment_whose_create_has_no_answer_yet_is_pending_for_the_gateway_and_shows_no_address() {
    PaymentView view = PaymentView.of(record(p -> p.actionUrl = "https://bank.example/3ds"), inFlight(OperationType.CREATE));

    assertThat(view.pendingReason()).isEqualTo("gateway_unknown");
    assertThat(view.nextAction()).isNull();
  }

  @Test
  void a_payment_with_an_address_for_the_customer_is_pending_for_the_customer() {
    PaymentView view = PaymentView.of(record(p -> p.actionUrl = "https://bank.example/3ds"), null);

    assertThat(view.pendingReason()).isEqualTo("customer_action");
    assertThat(view.nextAction()).isEqualTo(new PaymentView.NextAction("redirect", "https://bank.example/3ds"));
  }

  @Test
  void a_pending_payment_with_neither_has_no_reason() {
    PaymentView view = PaymentView.of(record(p -> { }), null);

    assertThat(view.pendingReason()).isNull();
    assertThat(view.nextAction()).isNull();
  }

  @Test
  void a_payment_that_is_not_pending_has_no_reason_and_no_next_action_whatever_else_is_set() {
    PaymentView captured = PaymentView.of(
        record(p -> {
          p.status = PaymentStatus.CAPTURED;
          p.captured = 15_000;
          p.actionUrl = "https://bank.example/stale";
        }),
        inFlight(OperationType.REFUND));

    assertThat(captured.pendingReason()).isNull();
    assertThat(captured.nextAction()).isNull();
    assertThat(captured.status()).isEqualTo("CAPTURED");
  }

  @Test
  void the_other_fields_are_copied_as_they_are() {
    PaymentView view = PaymentView.of(
        record(p -> {
          p.status = PaymentStatus.FAILED;
          p.failureCode = "declined";
          p.failureMessage = "INSUFFICIENT FUNDS";
          p.scheme = "mada";
          p.last4 = "0002";
        }),
        null);

    assertThat(view.id()).isEqualTo(ID);
    assertThat(view.amount()).isEqualTo(15_000);
    assertThat(view.currency()).isEqualTo("SAR");
    assertThat(view.reference()).isEqualTo("order-1");
    assertThat(view.method()).isEqualTo("CARD");
    assertThat(view.capture()).isEqualTo("AUTO");
    assertThat(view.failure()).isEqualTo(new PaymentView.Failure("declined", "INSUFFICIENT FUNDS"));
    assertThat(view.card()).isEqualTo(new PaymentView.Card("mada", "0002"));
    assertThat(view.description()).isEqualTo("Order 1");
    assertThat(view.version()).isEqualTo(3);
    assertThat(view.late()).isFalse();
    assertThat(view.createdAt()).isEqualTo(NOW);
  }
}
