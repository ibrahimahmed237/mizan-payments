package dev.mizan.core.sync;

import static dev.mizan.core.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.core.Fixtures;
import dev.mizan.core.payment.FollowUp;
import dev.mizan.core.payment.GatewayStatus;
import dev.mizan.core.payment.Payment;
import dev.mizan.core.payment.PaymentStatus;
import dev.mizan.core.sync.Effect.FollowUpRequested;
import dev.mizan.core.sync.Effect.Notify;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TimersTest {

  @Nested
  class A_pending_payment {

    private final Payment waiting = Fixtures.pending();
    private final Instant end = waiting.expiresAt();

    @Test
    void expires_exactly_when_its_waiting_time_ends() {
      Decision decision = Timers.expirePending(waiting, false, end);

      assertThat(decision.payment().status()).isEqualTo(PaymentStatus.EXPIRED);
      assertThat(decision.payment().actionUrl()).isNull();
      assertThat(decision.effects()).containsExactly(new Notify(EventType.PAYMENT_EXPIRED));
    }

    @Test
    void does_not_expire_one_moment_before() {
      assertThat(Timers.expirePending(waiting, false, end.minusMillis(1)).changedNothing()).isTrue();
    }

    @Test
    void does_not_expire_while_an_operation_is_in_flight() {
      assertThat(Timers.expirePending(waiting, true, end.plusSeconds(60)).changedNothing()).isTrue();
    }

    @Test
    void does_not_expire_before_the_gateway_was_asked() {
      assertThat(Timers.expirePending(Fixtures.fresh(), false, end.plusSeconds(60)).changedNothing()).isTrue();
    }

    @Test
    void only_a_pending_payment_expires_this_way() {
      Payment authorized = Fixtures.authorized().with(p -> p.expiresAt = NOW);

      assertThat(Timers.expirePending(authorized, false, NOW.plusSeconds(1)).changedNothing()).isTrue();
      assertThat(Timers.expirePending(Fixtures.pending().with(p -> p.expiresAt = null), false, NOW).changedNothing()).isTrue();
    }
  }

  @Nested
  class A_hold {

    private final Payment held = Fixtures.authorized();
    private final Instant end = held.holdEndsAt();

    @Test
    void expires_when_its_time_ran_out_and_is_voided() {
      Decision decision = Timers.expireHold(held, false, end);

      assertThat(decision.payment().status()).isEqualTo(PaymentStatus.EXPIRED);
      assertThat(decision.payment().pendingFollowUp()).isEqualTo(FollowUp.AUTO_VOID);
      assertThat(decision.effects()).containsExactly(new Notify(EventType.PAYMENT_EXPIRED), new FollowUpRequested(FollowUp.AUTO_VOID));
    }

    @Test
    void does_not_expire_before_its_end() {
      assertThat(Timers.expireHold(held, false, end.minus(Duration.ofMillis(1))).changedNothing()).isTrue();
    }

    @Test
    void does_not_expire_while_a_capture_is_in_flight() {
      assertThat(Timers.expireHold(held, true, end.plusSeconds(60)).changedNothing()).isTrue();
    }

    @Test
    void does_not_expire_when_the_gateway_already_said_something_else() {
      Payment voidedAtGateway = held.with(p -> p.gatewayStatus = GatewayStatus.VOIDED);

      assertThat(Timers.expireHold(voidedAtGateway, false, end).changedNothing()).isTrue();
    }

    @Test
    void is_not_voided_twice() {
      Payment voidedBefore = held.with(p -> p.followUpsStarted = EnumSet.of(FollowUp.AUTO_VOID));

      Decision decision = Timers.expireHold(voidedBefore, false, end);

      assertThat(decision.payment().status()).isEqualTo(PaymentStatus.EXPIRED);
      assertThat(decision.payment().pendingFollowUp()).isNull();
      assertThat(decision.effects()).containsExactly(new Notify(EventType.PAYMENT_EXPIRED));
    }

    @Test
    void only_an_authorized_payment_expires_this_way() {
      assertThat(Timers.expireHold(Fixtures.pending(), false, end).changedNothing()).isTrue();
      assertThat(Timers.expireHold(held.with(p -> p.holdEndsAt = null), false, end).changedNothing()).isTrue();
    }
  }
}
