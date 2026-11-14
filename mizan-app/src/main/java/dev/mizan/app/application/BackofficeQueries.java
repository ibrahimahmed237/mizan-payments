package dev.mizan.app.application;

import dev.mizan.app.application.model.DiscrepancyRecord;
import dev.mizan.app.application.port.Discrepancies;
import dev.mizan.app.application.port.LedgerQueries;
import dev.mizan.app.application.port.Payments;
import dev.mizan.app.application.port.Settlements;
import dev.mizan.app.application.view.DiscrepancyView;
import dev.mizan.app.application.view.SettlementFileView;
import dev.mizan.core.ledger.Account;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** What finance reads: settlement files, discrepancies, the books, and the reconciliation report. Changes nothing. */
@Service
public class BackofficeQueries {

  /** The balance of one account: debits minus credits. */
  public record Balance(String account, long debits, long credits, long balance) {}

  /** All accounts, and whether total debits equal total credits (invariant I1). */
  public record Balances(List<Balance> accounts, long totalDebits, long totalCredits, boolean balanced) {}

  /**
   * @param unsettled captured money that no file has paid out yet, net of refunds not taken back, by age of the capture
   * @param suspense the suspense account's balance: money received and not explained yet (a credit is positive here)
   */
  public record Reconciliation(Map<String, Long> unsettled, long suspense, List<Discrepancies.OpenOfType> openDiscrepancies) {}

  /** @param next where the next page starts; null when this was the last page */
  public record DiscrepancyPage(List<DiscrepancyView> discrepancies, Long next) {}

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

  /**
   * One page of discrepancies, newest first.
   *
   * @param status {@code OPEN}, {@code RESOLVED} or null for both
   * @param before the place the page before this one ended at; null for the first page
   */
  public DiscrepancyPage discrepancies(String status, String type, Long before, int limit) {
    Boolean open = switch (status == null ? "" : status) {
      case "" -> null;
      case "OPEN" -> true;
      case "RESOLVED" -> false;
      default -> throw Problem.invalid("invalid_status", "The status must be OPEN or RESOLVED.");
    };
    int size = Math.max(1, Math.min(limit, MAX_PAGE));
    List<DiscrepancyRecord> found = discrepancies.list(open, type, before, size + 1); // one more, to know if a page follows
    List<DiscrepancyRecord> page = found.size() > size ? found.subList(0, size) : found;
    return new DiscrepancyPage(
        page.stream().map(DiscrepancyView::of).toList(), found.size() > size ? page.get(page.size() - 1).seq() : null);
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
    Payments.Unsettled byAge = payments.unsettledByAge(clock.instant());
    Map<String, Long> unsettled = new LinkedHashMap<>();
    unsettled.put("up_to_3_days", byAge.upTo3Days());
    unsettled.put("3_to_7_days", byAge.from3To7Days());
    unsettled.put("over_7_days", byAge.over7Days());
    long suspense = -balances().accounts().stream().filter(b -> b.account().equals(Account.SUSPENSE.code())).findFirst().orElseThrow().balance();
    return new Reconciliation(unsettled, suspense, discrepancies.openByType());
  }
}
