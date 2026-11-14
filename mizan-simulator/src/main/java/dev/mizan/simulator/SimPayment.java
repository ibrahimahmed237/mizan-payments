package dev.mizan.simulator;

import dev.mizan.core.payment.Gateway;
import dev.mizan.core.payment.GatewayStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One payment as the simulator really has it: its own book. Mizan's copy is compared with this in the proof run.
 * All access is under the simulator's lock.
 */
public final class SimPayment {

  /** A capture or a refund, as it will appear in a settlement file. */
  public static final class Transaction {
    public final String id;
    public final boolean capture;
    public final long amount;
    public final Instant at;
    /** The payout this transaction was settled in; null while it waits. */
    public String payoutId;

    Transaction(String id, boolean capture, long amount, Instant at) {
      this.id = id;
      this.capture = capture;
      this.amount = amount;
      this.at = at;
    }
  }

  public final String id;
  public final Gateway gateway;
  /** The store's order number, as Mizan passed it. Faults are planned by it. */
  public final String reference;
  /** Mizan's own payment id, as Mizan passed it. */
  public final String mizanPaymentId;
  public final long amount;
  public final String currency;
  public final boolean manual;
  public final boolean needsCustomer;
  public final String callbackUrl;
  public final String scheme;
  public final String last4;
  public final boolean wallet;

  GatewayStatus status = GatewayStatus.INITIATED;
  long captured;
  long refunded;
  String failureMessage;
  /** How many times money was taken from the customer for this payment. More than one is a double charge. */
  int charges;
  /** How many times money was held for this payment. */
  int holds;
  boolean voidedAfterCapture;
  final List<Transaction> transactions = new ArrayList<>();
  final Map<String, Long> refundsByKey = new HashMap<>();
  int webhooksSent;

  SimPayment(
      String id, Gateway gateway, String reference, String mizanPaymentId, long amount, String currency, boolean manual,
      boolean needsCustomer, String callbackUrl, String scheme, String last4, boolean wallet) {
    this.id = id;
    this.gateway = gateway;
    this.reference = reference;
    this.mizanPaymentId = mizanPaymentId;
    this.amount = amount;
    this.currency = currency;
    this.manual = manual;
    this.needsCustomer = needsCustomer;
    this.callbackUrl = callbackUrl;
    this.scheme = scheme;
    this.last4 = last4;
    this.wallet = wallet;
  }

  public GatewayStatus status() {
    return status;
  }

  public long captured() {
    return captured;
  }

  public long refunded() {
    return refunded;
  }

  public int charges() {
    return charges;
  }

  public int holds() {
    return holds;
  }

  public List<Transaction> transactions() {
    return List.copyOf(transactions);
  }
}
