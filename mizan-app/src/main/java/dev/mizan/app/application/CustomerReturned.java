package dev.mizan.app.application;

import dev.mizan.app.application.model.PaymentRecord;
import dev.mizan.app.application.port.Payments;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The gateway sent the customer back to Mizan after the bank's page or the provider's page. Mizan looks at the
 * gateway once, so the store finds a fresh payment, and sends the customer on to the store. See docs/lld/workers.md.
 */
@Service
public class CustomerReturned {

  /** A payment looked at this recently is not looked at again: a stranger cannot use this page to flood the gateway. */
  public static final Duration FRESH_ENOUGH = Duration.ofSeconds(2);

  private static final Logger LOG = LoggerFactory.getLogger(CustomerReturned.class);

  private final Payments payments;
  private final SyncPayment sync;
  private final InstantSource clock;

  public CustomerReturned(Payments payments, SyncPayment sync, InstantSource clock) {
    this.payments = payments;
    this.sync = sync;
    this.clock = clock;
  }

  /** @return the store's address to send the customer to, with the payment's id added */
  public String handle(UUID paymentId) {
    PaymentRecord payment = payments.find(paymentId).orElseThrow(() -> Problem.notFound("payment"));
    if (payment.returnUrl() == null) {
      throw Problem.notFound("payment"); // a payment made without a return address has nowhere to send a customer
    }
    Instant now = clock.instant();
    boolean fresh = payment.lastSyncedAt() != null && payment.lastSyncedAt().plus(FRESH_ENOUGH).isAfter(now);
    if (!fresh) {
      try {
        sync.handle(paymentId);
      } catch (RuntimeException e) {
        // The customer goes on to the store whatever happens here. The sync worker looks again.
        LOG.warn("The look for the returning customer of payment {} failed", paymentId, e);
      }
    }
    return ReturnUrls.withPaymentId(payment.returnUrl(), paymentId.toString());
  }
}
