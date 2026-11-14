package dev.mizan.app.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mizan.app.application.port.Payments;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RunDuePaymentsTest {

  private static final Instant NOW = Instant.parse("2026-11-14T09:00:00Z");

  private final Payments payments = mock(Payments.class);
  private final AdvancePayment advance = mock(AdvancePayment.class);
  private final RunDuePayments round = new RunDuePayments(payments, advance, new Workers(1, 1), () -> NOW);

  @Test
  void every_payment_that_was_taken_is_advanced_and_the_lease_is_thirty_seconds() {
    UUID one = UUID.randomUUID();
    UUID two = UUID.randomUUID();
    when(payments.claimDue(NOW, NOW.plusSeconds(30), 20)).thenReturn(List.of(one, two));

    int taken = round.handle(20);

    assertThat(taken).isEqualTo(2);
    verify(advance).handle(one);
    verify(advance).handle(two);
  }

  @Test
  void one_payment_that_fails_does_not_stop_the_others() {
    UUID one = UUID.randomUUID();
    UUID broken = UUID.randomUUID();
    UUID three = UUID.randomUUID();
    when(payments.claimDue(any(), any(), eq(20))).thenReturn(List.of(one, broken, three));
    doThrow(new IllegalStateException("this payment cannot be advanced")).when(advance).handle(broken);

    int taken = round.handle(20);

    assertThat(taken).isEqualTo(3);
    verify(advance).handle(one);
    verify(advance).handle(three); // still advanced, after the failure
  }

  @Test
  void with_more_than_one_at_a_time_the_steps_run_side_by_side_and_one_failure_stops_no_other() {
    UUID one = UUID.randomUUID();
    UUID two = UUID.randomUUID();
    UUID broken = UUID.randomUUID();
    when(payments.claimDue(any(), any(), eq(20))).thenReturn(List.of(one, broken, two));
    // Each of the two good steps waits until the other has started too. One after another, they would never meet.
    CyclicBarrier bothStarted = new CyclicBarrier(2);
    AtomicInteger met = new AtomicInteger();
    when(advance.handle(any())).thenAnswer(step -> {
      if (step.getArgument(0).equals(broken)) {
        throw new IllegalStateException("this payment cannot be advanced");
      }
      bothStarted.await(5, TimeUnit.SECONDS);
      met.incrementAndGet();
      return null;
    });

    try (RunDuePayments sideBySide = new RunDuePayments(payments, advance, new Workers(3, 1), () -> NOW)) {
      assertThat(sideBySide.handle(20)).isEqualTo(3); // returns when every step is done
    }

    assertThat(met).hasValue(2);
  }

  @Test
  void a_round_with_nothing_due_does_nothing() {
    when(payments.claimDue(any(), any(), eq(20))).thenReturn(List.of());

    assertThat(round.handle(20)).isZero();
  }
}
