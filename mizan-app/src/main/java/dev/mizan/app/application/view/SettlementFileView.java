package dev.mizan.app.application.view;

import dev.mizan.app.application.model.LineStatus;
import dev.mizan.app.application.model.SettlementFileRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A settlement file and how far its matching has come.
 *
 * @param lines how many lines are in each status
 * @param complete every line is handled: matched, in suspense, or accepted
 */
public record SettlementFileView(
    UUID id, String gateway, String payoutId, LocalDate payoutDate, long totalNet, int lineCount, Map<String, Long> lines,
    boolean complete, Instant createdAt) {

  public static SettlementFileView of(SettlementFileRecord file, Map<LineStatus, Long> progress) {
    Map<String, Long> lines = new LinkedHashMap<>();
    for (LineStatus status : LineStatus.values()) {
      lines.put(status.name().toLowerCase(Locale.ROOT), progress.getOrDefault(status, 0L));
    }
    boolean complete = progress.getOrDefault(LineStatus.NEW, 0L) + progress.getOrDefault(LineStatus.NEEDS_SYNC, 0L) == 0;
    return new SettlementFileView(
        file.id(), file.gateway().name(), file.payoutId(), file.payoutDate(), file.totalNet(), file.lineCount(), lines, complete,
        file.createdAt());
  }
}
