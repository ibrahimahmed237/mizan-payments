package dev.mizan.core.sync;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.core.payment.OperationType;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class EventTypeTest {

  @Test
  void every_event_has_its_name_for_the_store() {
    assertThat(EnumSet.allOf(EventType.class)).allSatisfy(type -> assertThat(type.wireName()).matches("[a-z]+\\.[a-z_]+"));
    assertThat(EventType.PAYMENT_CAPTURED.wireName()).isEqualTo("payment.captured");
  }

  @Test
  void an_ended_operation_has_an_event_only_when_the_payments_own_event_does_not_say_it() {
    assertThat(EventType.forOperation(OperationType.REFUND, true)).isEqualTo(EventType.REFUND_SUCCEEDED);
    assertThat(EventType.forOperation(OperationType.REFUND, false)).isEqualTo(EventType.REFUND_FAILED);
    assertThat(EventType.forOperation(OperationType.CAPTURE, false)).isEqualTo(EventType.CAPTURE_FAILED);
    assertThat(EventType.forOperation(OperationType.CANCEL, false)).isEqualTo(EventType.CANCEL_FAILED);
    assertThat(EventType.forOperation(OperationType.CAPTURE, true)).isNull();
    assertThat(EventType.forOperation(OperationType.CANCEL, true)).isNull();
    assertThat(EventType.forOperation(OperationType.CREATE, true)).isNull();
    assertThat(EventType.forOperation(OperationType.CREATE, false)).isNull();
  }
}
