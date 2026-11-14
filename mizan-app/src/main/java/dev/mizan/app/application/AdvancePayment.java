package dev.mizan.app.application;

import dev.mizan.app.application.PaymentWriter.Saved;
import dev.mizan.app.application.SyncPayment.Look;
import dev.mizan.app.application.model.OperationRecord;
import dev.mizan.app.application.model.Origin;
import dev.mizan.app.application.model.PaymentRecord;
import dev.mizan.app.application.port.Operations;
import dev.mizan.app.application.port.Payments;
import dev.mizan.core.followup.FollowUps;
import dev.mizan.core.operation.OperationRules;
import dev.mizan.core.operation.Verdict;
import dev.mizan.core.payment.Payment;
import dev.mizan.core.sync.Decision;
import dev.mizan.core.sync.Timers;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * One step of the sync worker for one payment: look at the gateway, then do what is due on these fresh facts.
 * See docs/lld/workers.md.
 *
 * <ol>
 *   <li>Look: fetch the gateway's record and apply it.
 *   <li>An operation whose result is still not known: wait, send it once more, or end it without effect.
 *   <li>Otherwise the timers: a payment or a hold that waited too long ends.
 *   <li>Then a follow-up that waits: an automatic capture, the release of a late hold, the return of a late capture.
 * </ol>
 *
 * <p>Everything is decided in the transaction of the look. A call to the gateway is sent after it (rule R5).
 */
@Service
public class AdvancePayment {

  /** How long a payment that a worker took is left alone by other workers. */
  public static final Duration LEASE = Duration.ofSeconds(30);

  /** A call to send after the transaction. {@code operation} is null when there is none. */
  private record Send(PaymentRecord payment, OperationRecord operation) {

    static final Send NOTHING = new Send(null, null);
  }

  private final Payments payments;
  private final Operations operations;
  private final SyncPayment sync;
  private final PaymentWriter writer;
  private final OperationRunner runner;
  private final Settings settings;
  private final InstantSource clock;

  public AdvancePayment(
      Payments payments, Operations operations, SyncPayment sync, PaymentWriter writer, OperationRunner runner, Settings settings,
      InstantSource clock) {
    this.payments = payments;
    this.operations = operations;
    this.sync = sync;
    this.writer = writer;
    this.runner = runner;
    this.settings = settings;
    this.clock = clock;
  }

  /** @return the payment after the step */
  public PaymentRecord handle(UUID paymentId) {
    // Taken before the fetch: a request for a look that comes in from here on is not answered by this look.
    payments.claim(paymentId, clock.instant().plus(LEASE));

    Send send = sync.look(paymentId, this::decide);
    if (send.operation() != null) {
      runner.finish(paymentId, send.operation().id(), runner.send(send.payment(), send.operation()));
    }
    return payments.find(paymentId).orElseThrow();
  }

  private Send decide(Saved state, Look look, Instant now) {
    if (look == Look.FAILED) {
      return Send.NOTHING; // nothing is decided on old facts
    }
    if (state.inFlight() != null) {
      return unknownResult(state, look, now);
    }
    if (look == Look.NOTHING_TO_ASK) {
      return Send.NOTHING;
    }
    Saved current = state;
    Payment payment = current.payment().payment();
    for (Decision timer : List.of(Timers.expirePending(payment, false, now), Timers.expireHold(payment, false, now))) {
      if (!timer.changedNothing()) {
        current = writer.apply(current.payment(), null, timer, null, now);
      }
    }
    return followUp(current, now);
  }

  /** An operation in flight that the gateway's record did not settle. */
  private Send unknownResult(Saved state, Look look, Instant now) {
    OperationRecord operation = state.inFlight();
    Payment payment = state.payment().payment();
    if (look == Look.NOTHING_TO_ASK) {
      // A create with no id to ask about: its call left Mizan and no answer was written down.
      if (!now.isBefore(operation.lastAttemptAt().plus(settings.quietTime()))) {
        writer.apply(state.payment(), operation, OperationRules.unreachable(payment, operation.forRules()), null, now);
      }
      return Send.NOTHING;
    }
    Verdict verdict = OperationRules.afterSilence(operation.forRules(), payment.gateway(), settings.quietTime(), now);
    return switch (verdict) {
      case WAIT -> Send.NOTHING;
      case NO_EFFECT -> {
        writer.apply(state.payment(), operation, OperationRules.noEffect(payment, operation.forRules()), null, now);
        yield Send.NOTHING;
      }
      case SEND_AGAIN -> {
        // The second call is written down and committed before it is sent, like the first one.
        operations.attempt(operation.id(), operation.attempts() + 1, now);
        OperationRecord again = operations.find(operation.id()).orElseThrow();
        Saved saved = writer.apply(state.payment(), again, Decision.unchanged(payment), null, now);
        yield new Send(saved.payment(), again);
      }
    };
  }

  /** Starts the follow-up that waits, if it still applies. */
  private Send followUp(Saved state, Instant now) {
    Payment payment = state.payment().payment();
    if (payment.pendingFollowUp() == null) {
      return Send.NOTHING;
    }
    FollowUps.Start start = FollowUps.begin(payment, now);
    PaymentRecord begun = state.payment().withPayment(start.payment());
    if (start.nothingToSend()) {
      writer.apply(begun, null, Decision.unchanged(start.payment()), null, now);
      return Send.NOTHING;
    }
    OperationRecord operation = runner.start(begun, UUID.randomUUID(), start.operation(), Origin.SYSTEM, start.amount(), null, now);
    return new Send(begun, operation);
  }
}
