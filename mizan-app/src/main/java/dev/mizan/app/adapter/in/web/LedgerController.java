package dev.mizan.app.adapter.in.web;

import dev.mizan.app.application.BackofficeQueries;
import dev.mizan.app.application.port.LedgerQueries;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The books and the reconciliation report, for finance. */
@RestController
class LedgerController {

  private final BackofficeQueries queries;

  LedgerController(BackofficeQueries queries) {
    this.queries = queries;
  }

  @GetMapping("/v1/ledger/balances")
  BackofficeQueries.Balances balances() {
    return queries.balances();
  }

  @GetMapping("/v1/ledger/entries")
  Map<String, List<LedgerQueries.EntryRow>> entries(@RequestParam("payment_id") UUID paymentId) {
    return Map.of("data", queries.entries(paymentId));
  }

  @GetMapping("/v1/reports/reconciliation")
  BackofficeQueries.Reconciliation reconciliation() {
    return queries.reconciliation();
  }
}
