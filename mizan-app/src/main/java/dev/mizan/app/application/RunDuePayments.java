package dev.mizan.app.application;

import dev.mizan.app.application.port.Payments;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** One round of the sync worker: take the payments whose time has come, and advance each. */
@Service
public class RunDuePayments implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(RunDuePayments.class);

  private final Payments payments;
  private final AdvancePayment advance;
  private final SideBySide steps;
  private final InstantSource clock;

  public RunDuePayments(Payments payments, AdvancePayment advance, Workers workers, InstantSource clock) {
    this.payments = payments;
    this.advance = advance;
    this.steps = new SideBySide("sync", workers.syncParallel());
    this.clock = clock;
  }

  /** @return how many payments were taken */
  public int handle(int limit) {
    Instant now = clock.instant();
    List<UUID> due = payments.claimDue(now, now.plus(AdvancePayment.LEASE), limit);
    // Side by side: a step is mostly waiting for the gateway, and one slow answer must not hold up the rest.
    steps.forEach(due, this::step);
    return due.size();
  }

  private void step(UUID id) {
    try {
      advance.handle(id);
    } catch (RuntimeException e) {
      // One payment's trouble must not stop the others. Its lease ends, and it is taken again.
      LOG.error("The sync worker failed on payment {}", id, e);
    }
  }

  @Override
  public void close() {
    steps.close();
  }
}
