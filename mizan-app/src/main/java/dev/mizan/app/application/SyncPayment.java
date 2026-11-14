package dev.mizan.app.application;

import dev.mizan.app.application.PaymentWriter.Saved;
import dev.mizan.app.application.model.OperationRecord;
import dev.mizan.app.application.model.PaymentRecord;
import dev.mizan.app.application.port.GatewayResult;
import dev.mizan.app.application.port.Gateways;
import dev.mizan.app.application.port.Operations;
import dev.mizan.app.application.port.Payments;
import dev.mizan.app.application.port.Transactions;
import dev.mizan.core.sync.Decision;
import java.time.Instant;
import java.time.InstantSource;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Fetches the gateway's record of one payment and applies it (rule R1). The fetch runs with no transaction open
 * (rule R5); the record is applied in one transaction, under the payment's lock.
 */
@Service
public class SyncPayment {

  /** What a look at the gateway gave. */
  public enum Look {
    /** The gateway answered, and its record was applied. */
    FRESH,
    /** The gateway could not be asked. The payment is as Mizan last knew it. */
    FAILED,
    /** The gateway has given no id for this payment, so there is nothing to ask about. */
    NOTHING_TO_ASK
  }

  /**
   * @param inFlight the payment's operation in flight after the sync, or null
   * @param fetched false when the gateway could not be asked: the payment is as Mizan last knew it
   */
  public record Result(PaymentRecord payment, OperationRecord inFlight, boolean fetched) {}

  /** More work on the payment, in the same transaction as the look and under the same lock. */
  @FunctionalInterface
  public interface Then<T> {
    T apply(Saved state, Look look, Instant now);
  }

  private final Payments payments;
  private final Operations operations;
  private final Gateways gateways;
  private final PaymentWriter writer;
  private final Transactions transactions;
  private final InstantSource clock;

  public SyncPayment(
      Payments payments, Operations operations, Gateways gateways, PaymentWriter writer, Transactions transactions, InstantSource clock) {
    this.payments = payments;
    this.operations = operations;
    this.gateways = gateways;
    this.writer = writer;
    this.transactions = transactions;
    this.clock = clock;
  }

  public Result handle(UUID paymentId) {
    return look(paymentId, (state, look, now) -> new Result(state.payment(), state.inFlight(), look == Look.FRESH));
  }

  /** Looks at the gateway, applies what it says, and lets the caller decide more on these fresh facts. */
  public <T> T look(UUID paymentId, Then<T> then) {
    PaymentRecord known = payments.find(paymentId).orElseThrow(() -> Problem.notFound("payment"));
    GatewayResult answer = known.gatewayPaymentId() == null
        ? null
        : gateways.of(known.payment().gateway()).fetch(known.gatewayPaymentId());

    return transactions.run(() -> {
      Instant now = clock.instant();
      PaymentRecord locked = payments.lock(paymentId).orElseThrow();
      OperationRecord inFlight = operations.inFlight(paymentId).orElse(null);
      if (answer == null) {
        // Nothing changes; the save only sets when the worker looks next.
        Saved same = writer.apply(locked, inFlight, Decision.unchanged(locked.payment()), null, now);
        return then.apply(same, Look.NOTHING_TO_ASK, now);
      }
      if (answer instanceof GatewayResult.Done done) {
        return then.apply(writer.sync(locked, inFlight, done.snapshot(), now), Look.FRESH, now);
      }
      return then.apply(new Saved(writer.fetchFailed(locked, now), inFlight), Look.FAILED, now);
    });
  }
}
