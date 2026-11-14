package dev.mizan.core.operation;

/** What to do with an operation whose result is still not known after a fetch. */
public enum Verdict {
  /** Too early to decide: the call may still be on its way. */
  WAIT,
  /** Nothing changed and a repeat cannot move money twice: send the call once more. */
  SEND_AGAIN,
  /** Nothing changed and nothing more will be sent: the operation failed without effect. */
  NO_EFFECT
}
