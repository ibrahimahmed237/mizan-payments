package dev.mizan.app.application;

import dev.mizan.app.application.PaymentWriter.Saved;
import dev.mizan.app.application.model.IdempotencyRecord;
import dev.mizan.app.application.model.OperationRecord;
import dev.mizan.app.application.model.Origin;
import dev.mizan.app.application.model.PaymentRecord;
import dev.mizan.app.application.port.GatewayResult;
import dev.mizan.app.application.port.IdempotencyKeys;
import dev.mizan.app.application.port.Operations;
import dev.mizan.app.application.port.Payments;
import dev.mizan.app.application.port.Transactions;
import dev.mizan.app.application.view.OperationView;
import dev.mizan.core.operation.CommandRules;
import dev.mizan.core.operation.Refusal;
import dev.mizan.core.payment.OperationType;
import dev.mizan.core.payment.Payment;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** A capture, a cancel or a refund that the store asks for. See docs/lld/payments.md. */
@Service
public class RequestOperation {

  /**
   * @param amount for a capture or a refund; null means all that can be captured or refunded
   * @param reason free text, for a refund
   */
  public record Command(
      UUID paymentId, OperationType type, Long amount, String reason, String idempotencyKey, String requestHash) {}

  /** @param replay true when the key was used before and this is the operation it made then */
  public record Result(OperationView operation, boolean replay) {}

  private final Payments payments;
  private final Operations operations;
  private final IdempotencyKeys keys;
  private final SyncPayment sync;
  private final OperationRunner runner;
  private final PaymentWriter writer;
  private final Transactions transactions;
  private final Settings settings;
  private final InstantSource clock;

  public RequestOperation(
      Payments payments,
      Operations operations,
      IdempotencyKeys keys,
      SyncPayment sync,
      OperationRunner runner,
      PaymentWriter writer,
      Transactions transactions,
      Settings settings,
      InstantSource clock) {
    this.payments = payments;
    this.operations = operations;
    this.keys = keys;
    this.sync = sync;
    this.runner = runner;
    this.writer = writer;
    this.transactions = transactions;
    this.settings = settings;
    this.clock = clock;
  }

  /** What transaction 1 made. {@code toSend} is null when no call is needed. */
  private record Started(OperationRecord operation, PaymentRecord toSend, boolean replay) {}

  public Result handle(Command command) {
    // The rules are checked on fresh totals: a refund made in the gateway's dashboard counts.
    boolean fetched = sync.handle(command.paymentId()).fetched();

    // Transaction 1: check the rules under the lock and write the operation down as in flight.
    Started started = transactions.run(() -> start(command, fetched));
    if (started.replay() || started.toSend() == null) {
      return new Result(OperationView.of(started.operation()), started.replay());
    }

    // The call, with no transaction open; then transaction 2: what came of it.
    GatewayResult answer = runner.send(started.toSend(), started.operation());
    runner.finish(command.paymentId(), started.operation().id(), answer);
    return new Result(OperationView.of(operations.find(started.operation().id()).orElseThrow()), false);
  }

  private Started start(Command command, boolean fetched) {
    Instant now = clock.instant();
    UUID operationId = UUID.randomUUID();
    var key = new IdempotencyRecord(command.idempotencyKey(), command.requestHash(), null, operationId);
    if (!keys.save(key, now, now.plus(settings.idempotencyKeysKept()))) {
      return new Started(replay(keys.find(command.idempotencyKey()).orElseThrow(), command), null, true);
    }

    PaymentRecord locked = payments.lock(command.paymentId()).orElseThrow(() -> Problem.notFound("payment"));
    Payment payment = locked.payment();
    OperationRecord inFlight = operations.inFlight(locked.id()).orElse(null);
    long amount = switch (command.type()) {
      case CAPTURE -> command.amount() == null ? payment.amount() : command.amount();
      case REFUND -> command.amount() == null ? payment.refundable() : command.amount();
      case CANCEL, CREATE -> 0;
    };
    Optional<Refusal> refusal = switch (command.type()) {
      case CAPTURE -> CommandRules.capture(payment, amount, inFlight != null, now);
      case CANCEL -> CommandRules.cancel(payment, inFlight != null);
      case REFUND -> CommandRules.refund(payment, amount, inFlight != null);
      case CREATE -> throw new IllegalArgumentException("A payment is created by its own request.");
    };
    refusal.ifPresent(no -> {
      throw Problem.of(no);
    });

    if (command.type() == OperationType.CANCEL && CommandRules.cancelKind(payment) == CommandRules.Cancel.CANCEL_HERE) {
      // The customer has not paid, so no call is needed. But "has not paid" must be the gateway's word, just now.
      if (!fetched) {
        throw new Problem(
            Problem.Kind.UNAVAILABLE, "gateway_unavailable", "The gateway cannot be asked right now. Nothing was changed. Try again.");
      }
      var done = OperationRecord.done(operationId, locked.id(), OperationType.CANCEL, Origin.API, 0, command.reason(), now);
      operations.insert(done);
      writer.apply(locked, null, CommandRules.cancelHere(payment), null, now);
      return new Started(done, null, false);
    }

    OperationRecord operation = runner.start(locked, operationId, command.type(), Origin.API, amount, command.reason(), now);
    return new Started(operation, locked, false);
  }

  /** The key was used before: by this same request, or by another one. */
  private OperationRecord replay(IdempotencyRecord first, Command command) {
    // The hash covers the path, so the key of a payment never has an operation's hash.
    if (!first.requestHash().equals(command.requestHash())) {
      throw Problem.invalid("idempotency_key_reused", "This Idempotency-Key was used for another request.");
    }
    return operations.find(first.operationId()).orElseThrow();
  }
}
