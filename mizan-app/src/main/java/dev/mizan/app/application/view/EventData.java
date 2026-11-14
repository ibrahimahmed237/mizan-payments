package dev.mizan.app.application.view;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** The {@code data} object of an event for the store. */
public final class EventData {

  private EventData() {}

  /**
   * An event about a payment.
   *
   * @param operation the operation the event is about; null for a payment's own event
   */
  public static Map<String, Object> of(PaymentView payment, OperationView operation) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("payment", payment);
    data.put("operation", operation);
    return data;
  }

  /**
   * An event about a discrepancy that was opened.
   *
   * @param payment the payment it is about; null when Mizan has no such payment
   */
  public static Map<String, Object> discrepancy(UUID id, String type, PaymentView payment) {
    Map<String, Object> discrepancy = new LinkedHashMap<>();
    discrepancy.put("id", id);
    discrepancy.put("type", type);
    Map<String, Object> data = of(payment, null);
    data.put("discrepancy", discrepancy);
    return data;
  }
}
