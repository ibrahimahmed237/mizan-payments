package dev.mizan.app.application;

import dev.mizan.app.application.PaymentWriter.Saved;
import dev.mizan.app.application.model.OperationRecord;
import dev.mizan.app.application.model.Origin;
import dev.mizan.app.application.model.PaymentRecord;
import dev.mizan.app.application.port.GatewayClient;
import dev.mizan.app.application.port.GatewayResult;
import dev.mizan.app.application.port.Gateways;
import dev.mizan.app.application.port.Operations;
import dev.mizan.app.application.port.Payments;
import dev.mizan.app.application.port.Transactions;
import dev.mizan.core.operation.OperationRules;
import dev.mizan.core.payment.OperationType;
import dev.mizan.core.sync.Decision;
import dev.mizan.core.sync.Snapshot;
import java.time.Instant;
import java.time.InstantSource;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The three steps of every operation: write it down as in flight, send the call, and write down what came of it.
 * The call stands between two transactions, never inside one (rule R5). See docs/lld/payments.md.
 */
@Service
public class OperationRunner {

  private final Payments payments;
  private final Operations operations;
  private final Gateways gateways;
  private final PaymentWriter writer;
  private final Transactions transactions;
  private final InstantSource clock;

  public OperationRunner(
      Payments payments, Operations operations, Gateways gateways, PaymentWriter writer, Transactions transactions, InstantSource clock) {
    this.payments = payments;
    this.operations = operations;
    this.gateways = gateways;
    this.writer = writer;
    this.transactions = transactions;
    this.clock = clock;
  }

  /**
   * Step 1, inside the caller's transaction and under the payment's lock: the operation is in flight, with its
   * first call counted before it is sent. A crash after the commit cannot hide a call that may have left.
   */
  public OperationRecord start(PaymentRecord locked, UUID operationId, OperationType type, Origin origin, long amount, String reason, Instant now) {
    var operation = OperationRecord.inFlight(operationId, locked.id(), type, origin, amount, reason, now);
    operations.insert(operation);
    writer.apply(locked, operation, Decision.unchanged(locked.payment()), null, now); // sets when the worker looks next
    return operation;
  }

  /** Step 2, with no transaction open: the call. A create is not sent from here: only its request has the token. */
  public GatewayResult send(PaymentRecord payment, OperationRecord operation) {
    GatewayClient gateway = gateways.of(payment.payment().gateway());
    return switch (operation.type()) {
      case CAPTURE -> gateway.capture(payment.gatewayPaymentId(), operation.amount());
      case CANCEL -> gateway.cancel(payment.gatewayPaymentId());
      case REFUND -> gateway.refund(payment.gatewayPaymentId(), operation.amount(), operation.id().toString());
      case CREATE -> throw new IllegalStateException("A create is sent once, by the request that carries its token.");
    };
  }

  /**
   * Step 3: what came of the call.
   *
   * <p>An answer with a record is applied. After a "no" or no answer at all, the gateway is asked first: the money
   * may have moved anyway, and the record is the truth (rule R1). Only then is a clear "no" written down. With no
   * record and no clear "no", the operation stays in flight and the sync worker goes on from here.
   */
  public Saved finish(UUID paymentId, UUID operationId, GatewayResult answer) {
    String givenId = answer instanceof GatewayResult.Done done ? done.gatewayPaymentId() : null;
    Snapshot record = answer instanceof GatewayResult.Done done ? done.snapshot() : look(paymentId);

    return transactions.run(() -> {
      Instant now = clock.instant();
      PaymentRecord locked = payments.lock(paymentId).orElseThrow();
      if (locked.gatewayPaymentId() == null && givenId != null) {
        locked = locked.withGatewayPaymentId(givenId);
      }
      OperationRecord inFlight = operations.inFlight(paymentId).orElse(null);
      Saved saved = record == null ? new Saved(locked, inFlight) : writer.sync(locked, inFlight, record, now);

      OperationRecord open = saved.inFlight();
      if (open == null || !open.id().equals(operationId)) {
        return saved; // the record settled it, here or in a sync that ran meanwhile
      }
      if (answer instanceof GatewayResult.Refused refused) {
        Decision no = OperationRules.refused(saved.payment().payment(), open.forRules(), refused.code(), refused.message());
        return writer.apply(saved.payment(), open, no, null, now);
      }
      if (open.type() == OperationType.CREATE && saved.payment().gatewayPaymentId() == null) {
        // No answer, and no id to ask about later. Nothing can be paid there: the customer never got its address.
        return writer.apply(saved.payment(), open, OperationRules.unreachable(saved.payment().payment(), open.forRules()), null, now);
      }
      return saved;
    });
  }

  /** The gateway's record of the payment, or null when it cannot be had now. */
  private Snapshot look(UUID paymentId) {
    PaymentRecord payment = payments.find(paymentId).orElseThrow();
    if (payment.gatewayPaymentId() == null) {
      return null;
    }
    GatewayResult fetched = gateways.of(payment.payment().gateway()).fetch(payment.gatewayPaymentId());
    return fetched instanceof GatewayResult.Done done ? done.snapshot() : null;
  }
}
