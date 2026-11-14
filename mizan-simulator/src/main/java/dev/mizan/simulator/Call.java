package dev.mizan.simulator;

/** A call to a gateway that a fault can hit. */
public enum Call {
  CREATE,
  FETCH,
  CAPTURE,
  VOID,
  REFUND
}
