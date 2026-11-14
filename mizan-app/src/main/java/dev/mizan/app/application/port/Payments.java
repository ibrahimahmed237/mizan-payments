package dev.mizan.app.application.port;

import dev.mizan.app.application.model.PaymentRecord;
import dev.mizan.core.payment.Gateway;
import dev.mizan.core.payment.PaymentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The payments table. */
public interface Payments {

  /** Takes the order's lock for the rest of the transaction. Every change to a payment takes it first. */
  void lockOrder(String reference);

  /** The payment without a lock. For reading, and for deciding which gateway to ask before a transaction. */
  Optional<PaymentRecord> find(UUID id);

  /** Locks the order, then the payment's row, for the rest of the transaction. Empty when there is no such payment. */
  Optional<PaymentRecord> lock(UUID id);

  /** All payments of an order, oldest first. The caller holds the order's lock. */
  List<PaymentRecord> ofOrder(String reference);

  /** The payment with this id at this gateway, without a lock. */
  Optional<PaymentRecord> findByGatewayId(Gateway gateway, String gatewayPaymentId);

  void insert(PaymentRecord payment);

  /** Saves everything that can change. The caller holds the payment's lock. */
  void update(PaymentRecord payment);

  /**
   * Asks the sync worker to look at the payment now, and raises the payment's "look requested" flag. One statement,
   * no lock taken first: if a sync holds the payment's lock, this waits for it and is written after it.
   */
  void requestSync(UUID id, Instant now);

  /**
   * Pushes the payment's time to be looked at to {@code leaseEnds} and takes its "look requested" flag down: a
   * worker looks at it now, and that look answers every request made so far.
   */
  void claim(UUID id, Instant leaseEnds);

  /**
   * Takes up to {@code limit} payments whose time to be looked at has come, and pushes that time to
   * {@code leaseEnds} for each, as {@link #claim} does. This is a lease: another worker does not take them meanwhile, and if this worker
   * dies they come back when the lease ends.
   */
  List<UUID> claimDue(Instant now, Instant leaseEnds, int limit);

  /** Newest first. {@code before} is the place of the last payment of the page before; null for the first page. */
  List<PaymentRecord> list(String reference, PaymentStatus status, Place before, int limit);

  /** A place in the list of payments, newest first. */
  record Place(Instant createdAt, UUID id) {}
}
