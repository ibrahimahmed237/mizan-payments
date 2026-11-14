package dev.mizan.app.application.port;

import dev.mizan.app.application.model.LineRecord;
import dev.mizan.app.application.model.LineStatus;
import dev.mizan.app.application.model.SettlementFileRecord;
import dev.mizan.core.payment.Gateway;
import dev.mizan.core.settlement.SettlementLine;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The settlement files and their lines. */
public interface Settlements {

  /**
   * Saves a file and all its lines, waiting to be matched.
   *
   * @return false when the gateway's payout was imported before; nothing is saved then
   */
  boolean insert(SettlementFileRecord file, List<SettlementLine> lines, Instant now);

  Optional<SettlementFileRecord> findFile(Gateway gateway, String payoutId);

  Optional<SettlementFileRecord> file(UUID id);

  /** Newest first. */
  List<SettlementFileRecord> files(int limit);

  /** How many of the file's lines are in each status. */
  Map<LineStatus, Long> progress(UUID fileId);

  Optional<LineRecord> line(UUID id);

  /** Locks the line for the rest of the transaction. */
  Optional<LineRecord> lockLine(UUID id);

  /** An earlier line, in this file or an earlier one, has the same transaction of the same gateway. */
  boolean seenBefore(LineRecord line);

  /** Takes up to {@code limit} lines whose time has come, in file order, and pushes their time to {@code leaseEnds}. */
  List<UUID> claimDue(Instant now, Instant leaseEnds, int limit);

  /** The line did not fit. Its payment, when known, must be synced first. */
  void needsSync(UUID id, UUID paymentId, Instant now);

  /** The sync of its payment worked: the next try is the last. */
  void synced(UUID id);

  /** The sync of its payment did not work: try again then. */
  void retryAt(UUID id, Instant at);

  /** The line is handled: matched, or in suspense. */
  void handled(UUID id, LineStatus status, UUID paymentId, Instant now);

  /** Finance accepted the line's difference. */
  void accepted(UUID id);
}
