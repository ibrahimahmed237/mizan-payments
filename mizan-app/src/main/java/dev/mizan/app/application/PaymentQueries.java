package dev.mizan.app.application;

import dev.mizan.app.application.model.PaymentRecord;
import dev.mizan.app.application.port.Operations;
import dev.mizan.app.application.port.Payments;
import dev.mizan.app.application.view.OperationView;
import dev.mizan.app.application.view.PaymentView;
import dev.mizan.core.payment.OperationType;
import dev.mizan.core.payment.PaymentStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Reads payments. Changes nothing. */
@Service
public class PaymentQueries {

  /** A payment with everything that was done to it, oldest first. */
  public record Details(PaymentView payment, List<OperationView> operations) {}

  /**
   * @param next the place to continue from for the next page; null when this is the last page
   */
  public record Page(List<PaymentView> payments, Payments.Place next) {}

  public static final int MAX_PAGE = 100;

  private final Payments payments;
  private final Operations operations;

  public PaymentQueries(Payments payments, Operations operations) {
    this.payments = payments;
    this.operations = operations;
  }

  public Details details(UUID id) {
    PaymentRecord payment = payments.find(id).orElseThrow(() -> Problem.notFound("payment"));
    return new Details(view(payment), operations.ofPayment(id, null).stream().map(OperationView::of).toList());
  }

  public List<OperationView> refunds(UUID id) {
    payments.find(id).orElseThrow(() -> Problem.notFound("payment"));
    return operations.ofPayment(id, OperationType.REFUND).stream().map(OperationView::of).toList();
  }

  /** Newest first. */
  public Page list(String reference, PaymentStatus status, Payments.Place after, int limit) {
    int size = Math.max(1, Math.min(limit, MAX_PAGE));
    List<PaymentRecord> found = payments.list(reference, status, after, size + 1); // one more, to know if a page follows
    List<PaymentRecord> page = found.size() > size ? found.subList(0, size) : found;
    PaymentRecord last = page.isEmpty() ? null : page.get(page.size() - 1);
    return new Page(
        page.stream().map(this::view).toList(),
        found.size() > size ? new Payments.Place(last.createdAt(), last.id()) : null);
  }

  public PaymentView view(PaymentRecord payment) {
    return PaymentView.of(payment, operations.inFlight(payment.id()).orElse(null));
  }
}
