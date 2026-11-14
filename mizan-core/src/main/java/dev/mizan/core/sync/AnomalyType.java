package dev.mizan.core.sync;

/** A gateway record that cannot be this payment. */
public enum AnomalyType {
  /** The record has another amount or currency than the payment. */
  AMOUNT_CONFLICT
}
