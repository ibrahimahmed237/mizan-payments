package dev.mizan.app.application;

import dev.mizan.app.application.model.SettlementFileRecord;
import dev.mizan.app.application.port.Settlements;
import dev.mizan.app.application.port.Transactions;
import dev.mizan.core.settlement.SettlementFile;
import dev.mizan.core.settlement.SettlementFileException;
import dev.mizan.core.settlement.SettlementFileParser;
import java.io.InputStreamReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.InstantSource;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Takes in one settlement file: checked as a whole, then saved with all its lines. Matching is the matching
 * worker's job. A file that cannot be trusted as a whole is refused, and nothing of it is saved.
 * See docs/lld/reconciliation.md, "Step 1".
 */
@Service
public class ImportSettlement {

  /**
   * @param replay true when this payout was imported before with the same content: nothing new was saved
   */
  public record Result(SettlementFileRecord file, boolean replay) {}

  private final Settlements settlements;
  private final Transactions transactions;
  private final InstantSource clock;

  public ImportSettlement(Settlements settlements, Transactions transactions, InstantSource clock) {
    this.settlements = settlements;
    this.transactions = transactions;
    this.clock = clock;
  }

  public Result handle(byte[] content) {
    SettlementFile file;
    try {
      file = SettlementFileParser.parse(new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8));
    } catch (SettlementFileException e) {
      throw Problem.invalid(e.code(), e.getMessage());
    }
    String hash = sha256(content);

    return transactions.run(() -> {
      Instant now = clock.instant();
      var header = file.header();
      var record = new SettlementFileRecord(
          UUID.randomUUID(), header.gateway(), header.payoutId(), header.payoutDate(), header.totalNet(), header.lineCount(), hash, now);
      if (settlements.insert(record, file.lines(), now)) {
        return new Result(record, false);
      }
      SettlementFileRecord first = settlements.findFile(header.gateway(), header.payoutId()).orElseThrow();
      if (!first.sha256().equals(hash)) {
        throw new Problem(Problem.Kind.CONFLICT, "payout_exists", "This payout was imported before, with other content.");
      }
      return new Result(first, true);
    });
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
