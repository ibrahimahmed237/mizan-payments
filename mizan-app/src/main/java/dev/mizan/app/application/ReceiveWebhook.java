package dev.mizan.app.application;

import dev.mizan.app.application.model.PaymentRecord;
import dev.mizan.app.application.port.Discrepancies;
import dev.mizan.app.application.port.GatewayEvents;
import dev.mizan.app.application.port.Outbox;
import dev.mizan.app.application.port.Payments;
import dev.mizan.app.application.port.Transactions;
import dev.mizan.app.application.view.EventData;
import dev.mizan.core.payment.Gateway;
import dev.mizan.core.sync.EventType;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * A webhook from a gateway came in and was checked. It is a hint, nothing more (rule R2): Mizan writes down that it
 * came and asks the sync worker to look at the payment. It does not call the gateway and does not change the
 * payment, so it is fast and cannot be fooled by what the webhook says. See docs/lld/workers.md.
 */
@Service
public class ReceiveWebhook {

  /**
   * What Mizan takes from a webhook.
   *
   * @param eventId the gateway's id of the event; the same event is acted on once
   * @param type the gateway's name of the event, kept only to be read by people
   * @param gatewayPaymentId the payment's id at the gateway
   * @param mizanPaymentId Mizan's own payment id, when the gateway carries it; null otherwise
   */
  public record Event(Gateway gateway, String eventId, String type, String gatewayPaymentId, UUID mizanPaymentId) {}

  /** What came of a webhook. */
  public enum Outcome {
    /** About a payment of Mizan's: a look is asked for. */
    ACCEPTED,
    /** The same event again. Nothing more happens. */
    REPEATED,
    /** About a payment Mizan does not have. */
    UNMATCHED
  }

  /** Why a webhook about a payment that Mizan does not have is worth a person's look. */
  public static final String UNKNOWN_PAYMENT_EVENT = "UNKNOWN_PAYMENT_EVENT";

  private final Payments payments;
  private final GatewayEvents events;
  private final Discrepancies discrepancies;
  private final Outbox outbox;
  private final Transactions transactions;
  private final InstantSource clock;

  public ReceiveWebhook(
      Payments payments, GatewayEvents events, Discrepancies discrepancies, Outbox outbox, Transactions transactions, InstantSource clock) {
    this.payments = payments;
    this.events = events;
    this.discrepancies = discrepancies;
    this.outbox = outbox;
    this.transactions = transactions;
    this.clock = clock;
  }

  public Outcome handle(Event event) {
    return transactions.run(() -> {
      Instant now = clock.instant();
      Optional<PaymentRecord> payment = find(event);
      UUID paymentId = payment.map(PaymentRecord::id).orElse(null);
      if (!events.save(event.gateway(), event.eventId(), event.type(), event.gatewayPaymentId(), paymentId, now)) {
        return Outcome.REPEATED; // the same event again: it was acted on the first time
      }
      if (paymentId != null) {
        payments.requestSync(paymentId, now);
        return Outcome.ACCEPTED;
      }
      // Someone took a payment at the gateway outside Mizan. One discrepancy per such payment, however many events come.
      discrepancies.openForGatewayPayment(UNKNOWN_PAYMENT_EVENT, event.gateway(), event.gatewayPaymentId(), now)
          .ifPresent(opened -> outbox.add(
              EventType.DISCREPANCY_OPENED, null, EventData.discrepancy(opened, UNKNOWN_PAYMENT_EVENT, null), now));
      return Outcome.UNMATCHED;
    });
  }

  /**
   * The payment the event is about: by the gateway's id, or, when Mizan never learned that id, by Mizan's own id
   * that the gateway carries. The second case is a create whose answer was lost: now Mizan can ask about it.
   */
  private Optional<PaymentRecord> find(Event event) {
    Optional<PaymentRecord> known = payments.findByGatewayId(event.gateway(), event.gatewayPaymentId());
    if (known.isPresent() || event.mizanPaymentId() == null) {
      return known;
    }
    Optional<PaymentRecord> own = payments.lock(event.mizanPaymentId())
        .filter(payment -> payment.payment().gateway() == event.gateway() && payment.gatewayPaymentId() == null);
    own.ifPresent(payment -> payments.update(payment.withGatewayPaymentId(event.gatewayPaymentId())));
    return own;
  }
}
