package dev.mizan.app.application;

import dev.mizan.app.application.port.Settlements;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** One round of the matching worker: take the lines whose time has come, in file order, and match each. */
@Service
public class RunDueLines {

  private static final Logger LOG = LoggerFactory.getLogger(RunDueLines.class);

  private final Settlements settlements;
  private final MatchLine match;
  private final InstantSource clock;

  public RunDueLines(Settlements settlements, MatchLine match, InstantSource clock) {
    this.settlements = settlements;
    this.match = match;
    this.clock = clock;
  }

  /** @return how many lines were taken */
  public int handle(int limit) {
    Instant now = clock.instant();
    List<UUID> due = settlements.claimDue(now, now.plus(MatchLine.LEASE), limit);
    for (UUID id : due) {
      try {
        match.handle(id);
      } catch (RuntimeException e) {
        // One line's trouble must not stop the others. Its lease ends, and it is taken again.
        LOG.error("The matching worker failed on settlement line {}", id, e);
      }
    }
    return due.size();
  }
}
