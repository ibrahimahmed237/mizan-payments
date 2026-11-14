package dev.mizan.app.application;

import dev.mizan.core.operation.Refusal;

/** A request that Mizan will not carry out, with the reason for the caller. Nothing was changed. */
public final class Problem extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** What kind of "no". The web layer turns it into an HTTP status. */
  public enum Kind {
    /** The request itself is wrong: a missing header, a body that cannot be used. */
    BAD_REQUEST,
    /** The caller did not prove who it is. */
    UNAUTHORIZED,
    NOT_FOUND,
    /** The body is larger than Mizan reads. */
    TOO_LARGE,
    /** The state of the payment or the order does not allow it. */
    CONFLICT,
    /** A value breaks a rule. */
    INVALID,
    /** Mizan cannot do it right now. Safe to send again. */
    UNAVAILABLE
  }

  private final Kind kind;
  private final String code;

  public Problem(Kind kind, String code, String message) {
    super(message);
    this.kind = kind;
    this.code = code;
  }

  public static Problem of(Refusal refusal) {
    return new Problem(refusal.conflict() ? Kind.CONFLICT : Kind.INVALID, refusal.code(), refusal.message());
  }

  public static Problem invalid(String code, String message) {
    return new Problem(Kind.INVALID, code, message);
  }

  public static Problem notFound(String what) {
    return new Problem(Kind.NOT_FOUND, "not_found", "No such " + what + ".");
  }

  public Kind kind() {
    return kind;
  }

  public String code() {
    return code;
  }
}
