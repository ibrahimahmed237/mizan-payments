package dev.mizan.app.application.model;

/** Where an operation stands. */
public enum OperationStatus {
  /** Sent to the gateway; the result is not known yet. */
  IN_FLIGHT,
  SUCCEEDED,
  FAILED
}
