package dev.mizan.core.settlement;

import java.util.List;

/** A settlement file that was read and checked as a whole. */
public record SettlementFile(SettlementHeader header, List<SettlementLine> lines) {

  public SettlementFile {
    lines = List.copyOf(lines);
  }
}
