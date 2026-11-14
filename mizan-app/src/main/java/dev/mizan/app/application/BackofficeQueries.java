package dev.mizan.app.application;

import dev.mizan.app.application.port.Discrepancies;
import dev.mizan.app.application.port.LedgerQueries;
import dev.mizan.app.application.port.Payments;
import dev.mizan.app.application.port.Settlements;
import dev.mizan.app.application.view.DiscrepancyView;
import dev.mizan.app.application.view.SettlementFileView;
import dev.mizan.core.ledger.Account;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** What finance reads: settlement files, discrepancies, the books, and the reconciliation report. Changes nothing. */
@Service
public class BackofficeQueries {

  /** The balance of one account: debits minus credits. */
  public record Balance(String account, long debits, long credits, long balance) {}

  /** All accounts, and whether total debits equal total credits (invariant I1). */
  public record Balances(List<Balance> accounts, long totalDebits, long totalCredits, boolean balanced) {}

  /** Open discrepancies of one type. */
  public record OpenOfType(String type, long count, long suspenseAmount) {}

  /**
   * @param unsettled captured money that no file has paid out yet, net of refunds not taken back, by age of the capture
   * @param suspense the suspense account's balance: money received and not explained yet (a credit is positive here)
   */
  public record Reconciliation(Map<String, Long> unsettled, long suspense, List<OpenOfType> openDiscrepancies) {}

  public static final int MAX_PAGE = 100;

  private final Settlements settlements;
  private final Discrepancies discrepancies;
  private final LedgerQueries ledger;
  private final Payments payments;
  private final InstantSource clock;

  public BackofficeQueries(
      Settlements settlements, Discrepancies discrepancies, LedgerQueries ledger, Payments payments, InstantSource clock) {
    this.settlements = settlements;
    this.discrepancies = discrepancies;
    this.ledger = ledger;
    this.payments = payments;
    this.clock = clock;
  }

  public SettlementFileView file(UUID id) {
    var file = settlements.file(id).orElseThrow(() -> Problem.notFound("settlement file"));
    return SettlementFileView.of(file, settlements.progress(id));
  }

  public List<SettlementFileView> files() {
    return settlements.files(MAX_PAGE).stream().map(file -> SettlementFileView.of(file, settlements.progress(file.id()))).toList();
  }

  public DiscrepancyView discrepancy(UUID id) {
    return DiscrepancyView.of(discrepancies.find(id).orElseThrow(() -> Problem.notFound("discrepancy")));
  }

  /** @param status {@code OPEN}, {@code RESOLVED} or null for both */
  public List<DiscrepancyView> discrepancies(String status, String type) {
    Boolean open = switch (status == null ? "" : status) {
      case "" -> null;
      case "OPEN" -> true;
      case "RESOLVED" -> false;
      default -> throw Problem.invalid("invalid_status", "The status must be OPEN or RESOLVED.");
    };
    return discrepancies.list(open, type, MAX_PAGE).stream().map(DiscrepancyView::of).toList();
  }

  public Balances balances() {
    Map<String, long[]> totals = ledger.totals();
    long debits = 0;
    long credits = 0;
    var accounts = new ArrayList<Balance>();
    for (Account account : Account.values()) {
      long[] sides = totals.getOrDefault(account.code(), new long[2]);
      accounts.add(new Balance(account.code(), sides[0], sides[1], sides[0] - sides[1]));
      debits += sides[0];
      credits += sides[1];
    }
    return new Balances(accounts, debits, credits, debits == credits);
  }

  public List<LedgerQueries.EntryRow> entries(UUID paymentId) {
    payments.find(paymentId).orElseThrow(() -> Problem.notFound("payment"));
    return ledger.entriesOf(paymentId);
  }

  public Reconciliation reconciliation() {
    var now = clock.instant();
    Map<String, Long> unsettled = new LinkedHashMap<>();
    unsettled.put("up_to_3_days", 0L);
    unsettled.put("3_to_7_days", 0L);
    unsettled.put("over_7_days", 0L);
    for (var payment : payments.unsettledSince(now)) {
      var view = payment.forSettlement();
      long amount = view.unsettledCaptured() - view.unsettledRefunded();
      var since = payment.payment().capturedAt();
      Duration age = since == null ? Duration.ZERO : Duration.between(since, now);
      String bucket = age.compareTo(Duration.ofDays(3)) < 0 ? "up_to_3_days" : age.compareTo(Duration.ofDays(7)) < 0 ? "3_to_7_days" : "over_7_days";
      unsettled.merge(bucket, amount, Long::sum);
    }
    long suspense = -balances().accounts().stream().filter(b -> b.account().equals(Account.SUSPENSE.code())).findFirst().orElseThrow().balance();
    Map<String, long[]> byType = new TreeMap<>();
    for (var open : discrepancies.list(true, null, Integer.MAX_VALUE)) {
      long[] row = byType.computeIfAbsent(open.type(), t -> new long[2]);
      row[0]++;
      row[1] += open.suspenseAmount();
    }
    List<OpenOfType> openDiscrepancies = byType.entrySet().stream()
        .map(e -> new OpenOfType(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();
    return new Reconciliation(unsettled, suspense, openDiscrepancies);
  }
}
