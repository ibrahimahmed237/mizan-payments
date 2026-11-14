package dev.mizan.app.application.port;

import dev.mizan.app.application.model.OperationRecord;
import dev.mizan.core.payment.OperationType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The operations table. */
public interface Operations {

  void insert(OperationRecord operation);

  Optional<OperationRecord> find(UUID id);

  /** The payment's operation in flight. There is at most one (rule R4). */
  Optional<OperationRecord> inFlight(UUID paymentId);

  /** Oldest first. With a type: only that type. */
  List<OperationRecord> ofPayment(UUID paymentId, OperationType type);

  /** Closes an operation in flight. */
  void finish(OperationRecord finished);

  /** Writes down a call that is about to be sent again. */
  void attempt(UUID id, int attempts, Instant at);
}
