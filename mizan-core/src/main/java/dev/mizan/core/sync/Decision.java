package dev.mizan.core.sync;

import dev.mizan.core.payment.Payment;
import java.util.List;
import java.util.Objects;

/** The payment after a decision, and what the caller must write down. */
public record Decision(Payment payment, List<Effect> effects) {

  public Decision {
    Objects.requireNonNull(payment, "payment");
    effects = List.copyOf(effects);
  }

  /** Nothing changed. */
  public static Decision unchanged(Payment payment) {
    return new Decision(payment, List.of());
  }

  public boolean changedNothing() {
    return effects.isEmpty();
  }

  /** The effects of one kind. */
  public <E extends Effect> List<E> effects(Class<E> kind) {
    return effects.stream().filter(kind::isInstance).map(kind::cast).toList();
  }

  public boolean has(Class<? extends Effect> kind) {
    return effects.stream().anyMatch(kind::isInstance);
  }
}
