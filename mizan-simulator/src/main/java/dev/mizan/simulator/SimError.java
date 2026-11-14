package dev.mizan.simulator;

/** A clear "no" from the simulated gateway: an HTTP status, a type and a message. */
public final class SimError extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int status;
  private final String type;

  public SimError(int status, String type, String message) {
    super(message);
    this.status = status;
    this.type = type;
  }

  public int status() {
    return status;
  }

  public String type() {
    return type;
  }
}
