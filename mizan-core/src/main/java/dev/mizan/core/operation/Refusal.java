package dev.mizan.core.operation;

/**
 * Why a command was not started.
 *
 * @param conflict true when the payment's state does not allow it (HTTP 409); false when a value breaks a rule (422)
 */
public record Refusal(String code, String message, boolean conflict) {

  static Refusal conflict(String code, String message) {
    return new Refusal(code, message, true);
  }

  static Refusal invalid(String code, String message) {
    return new Refusal(code, message, false);
  }
}
