package dev.mizan.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mizan.app.application.port.Transactions;
import dev.mizan.app.support.IntegrationTest;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/**
 * The rules the database enforces by itself, each one tested by trying to break it with plain SQL. The code checks
 * the same rules first; these are the last line. See docs/lld/data.md.
 */
class SchemaIT extends IntegrationTest {

  private final Transactions transactions = world.app.getBean(Transactions.class);

  @Test
  void the_schema_builds_from_nothing_and_a_second_run_changes_nothing() {
    Flyway flyway = world.app.getBean(Flyway.class);

    assertThat(flyway.info().applied()).isNotEmpty();
    assertThat(flyway.migrate().migrationsExecuted).isZero();
    assertThat(flyway.info().pending()).isEmpty();
  }

  // ---------------------------------------------------------------- payments

  @Test
  void a_payment_cannot_have_more_refunded_than_captured_or_more_captured_than_its_amount() {
    UUID id = payment("CAPTURED", 1_000, 1_000, 0);

    refused("payments_limits", () -> jdbc.sql("UPDATE payments SET refunded_amount = 1001 WHERE id = ?").param(id).update());
    refused("payments_limits", () -> jdbc.sql("UPDATE payments SET captured_amount = 1001 WHERE id = ?").param(id).update());
    refused("payments_limits", () -> jdbc.sql("UPDATE payments SET refunded_amount = -1 WHERE id = ?").param(id).update());
  }

  @Test
  void a_payment_cannot_have_more_settled_than_captured_or_refunded() {
    UUID id = payment("CAPTURED", 1_000, 1_000, 400);

    refused("payments_settled", () -> jdbc.sql("UPDATE payments SET settled_captured = 1001 WHERE id = ?").param(id).update());
    refused("payments_settled", () -> jdbc.sql("UPDATE payments SET settled_refunded = 401 WHERE id = ?").param(id).update());
    refused("payments_settled", () -> jdbc.sql("UPDATE payments SET settled_captured = -1 WHERE id = ?").param(id).update());
  }

  @Test
  void a_payment_is_captured_exactly_when_something_was_captured() {
    UUID pending = payment("PENDING", 1_000, 0, 0);
    UUID captured = payment("CAPTURED", 1_000, 1_000, 0);

    refused("payments_captured_has_money", () -> jdbc.sql("UPDATE payments SET captured_amount = 500 WHERE id = ?").param(pending).update());
    refused("payments_captured_has_money", () -> jdbc.sql("UPDATE payments SET status = 'FAILED' WHERE id = ?").param(captured).update());
  }

  @Test
  void a_payment_has_a_known_status_method_and_gateway_and_an_amount_above_zero() {
    UUID id = payment("PENDING", 1_000, 0, 0);

    refused("payments_status_check", () -> jdbc.sql("UPDATE payments SET status = 'PAID' WHERE id = ?").param(id).update());
    refused("payments_method_check", () -> jdbc.sql("UPDATE payments SET method = 'CASH' WHERE id = ?").param(id).update());
    refused("payments_gateway_check", () -> jdbc.sql("UPDATE payments SET gateway = 'OTHER' WHERE id = ?").param(id).update());
    refused("payments_amount_check", () -> jdbc.sql("UPDATE payments SET amount = 0 WHERE id = ?").param(id).update());
  }

  @Test
  void two_payments_cannot_have_the_same_id_at_one_gateway() {
    UUID first = payment("PENDING", 1_000, 0, 0);
    UUID second = payment("PENDING", 1_000, 0, 0);
    jdbc.sql("UPDATE payments SET gateway_payment_id = ? WHERE id = ?").param("gw-" + first).param(first).update();

    refused("payments_gateway_payment", () -> jdbc.sql("UPDATE payments SET gateway_payment_id = ? WHERE id = ?").param("gw-" + first).param(second).update());
  }

  // ---------------------------------------------------------------- operations

  @Test
  void a_payment_has_at_most_one_operation_in_flight() {
    UUID id = payment("CAPTURED", 1_000, 1_000, 0);
    operation(id, "REFUND", "IN_FLIGHT");

    refused("operations_one_in_flight", () -> operation(id, "REFUND", "IN_FLIGHT"));
    operation(id, "REFUND", "SUCCEEDED"); // finished operations are not limited
  }

  @Test
  void an_operation_in_flight_has_a_call_written_down() {
    UUID id = payment("CAPTURED", 1_000, 1_000, 0);

    refused("operations_in_flight_was_sent", () -> jdbc.sql("""
        INSERT INTO operations (id, payment_id, type, status, origin, attempts, created_at)
        VALUES (?, ?, 'REFUND', 'IN_FLIGHT', 'API', 0, now())
        """).param(UUID.randomUUID()).param(id).update());
  }

  @Test
  void an_operation_is_finished_exactly_when_it_is_not_in_flight() {
    UUID id = payment("CAPTURED", 1_000, 1_000, 0);
    UUID inFlight = operation(id, "REFUND", "IN_FLIGHT");

    refused("operations_finished", () -> jdbc.sql("UPDATE operations SET finished_at = now() WHERE id = ?").param(inFlight).update());
    refused("operations_finished", () -> jdbc.sql("UPDATE operations SET status = 'FAILED' WHERE id = ?").param(inFlight).update());
  }

  @Test
  void an_idempotency_key_points_to_one_thing_and_is_unique() {
    UUID id = payment("PENDING", 1_000, 0, 0);
    UUID operation = operation(id, "CANCEL", "SUCCEEDED");
    String key = "key-" + UUID.randomUUID();
    jdbc.sql("INSERT INTO idempotency_keys (key, request_hash, payment_id, created_at, expires_at) VALUES (?, 'h', ?, now(), now())")
        .param(key).param(id).update();

    refused("idempotency_keys_pkey", () -> jdbc.sql(
        "INSERT INTO idempotency_keys (key, request_hash, payment_id, created_at, expires_at) VALUES (?, 'h', ?, now(), now())")
        .param(key).param(id).update());
    refused("idempotency_keys_one_target", () -> jdbc.sql(
        "INSERT INTO idempotency_keys (key, request_hash, payment_id, operation_id, created_at, expires_at) VALUES (?, 'h', ?, ?, now(), now())")
        .param(key + "-2").param(id).param(operation).update());
    refused("idempotency_keys_one_target", () -> jdbc.sql(
        "INSERT INTO idempotency_keys (key, request_hash, created_at, expires_at) VALUES (?, 'h', now(), now())")
        .param(key + "-3").update());
  }

  // ---------------------------------------------------------------- the ledger

  @Test
  void an_entry_whose_debits_and_credits_differ_is_refused_at_commit() {
    refused("does not balance", () -> transactions.run(() -> {
      UUID entry = entry("unbalanced-" + UUID.randomUUID());
      posting(entry, "gateway_receivable", "DEBIT", 1_000);
      posting(entry, "sales", "CREDIT", 999);
    }));
  }

  @Test
  void an_entry_with_one_side_or_with_no_postings_is_refused_at_commit() {
    refused("does not balance", () -> transactions.run(() -> {
      posting(entry("one-side-" + UUID.randomUUID()), "gateway_receivable", "DEBIT", 1_000);
    }));
    refused("does not balance", () -> transactions.run(() -> {
      entry("empty-" + UUID.randomUUID());
    }));
  }

  @Test
  void a_balanced_entry_is_accepted_and_its_key_is_unique() {
    String key = "balanced-" + UUID.randomUUID();
    transactions.run(() -> balanced(key));

    refused("ledger_entries_dedupe_key_key", () -> transactions.run(() -> balanced(key)));
  }

  @Test
  void entries_and_postings_cannot_be_changed_deleted_or_emptied() {
    String key = "kept-" + UUID.randomUUID();
    UUID entry = transactions.run(() -> balanced(key));

    refused("append-only", () -> jdbc.sql("UPDATE ledger_entries SET kind = 'REFUND' WHERE id = ?").param(entry).update());
    refused("append-only", () -> jdbc.sql("DELETE FROM ledger_entries WHERE id = ?").param(entry).update());
    refused("append-only", () -> jdbc.sql("UPDATE ledger_postings SET amount = 1 WHERE entry_id = ?").param(entry).update());
    refused("append-only", () -> jdbc.sql("DELETE FROM ledger_postings WHERE entry_id = ?").param(entry).update());
    refused("append-only", () -> jdbc.sql("TRUNCATE ledger_postings").update());
    refused("append-only", () -> jdbc.sql("TRUNCATE ledger_entries CASCADE").update()); // the postings go with it, and are refused
    refused("ledger_postings", () -> jdbc.sql("TRUNCATE ledger_entries").update()); // without CASCADE, the foreign key refuses it
  }

  @Test
  void a_posting_cannot_join_an_entry_of_an_earlier_transaction() {
    UUID entry = transactions.run(() -> balanced("closed-" + UUID.randomUUID()));

    // Even a balanced pair is refused: an entry, once written, is closed.
    refused("is closed", () -> transactions.run(() -> {
      posting(entry, "gateway_receivable", "DEBIT", 5);
      posting(entry, "sales", "CREDIT", 5);
    }));
  }

  @Test
  void a_posting_has_an_amount_above_zero_and_a_known_account_and_side() {
    refused("ledger_postings_amount_check", () -> transactions.run(() -> {
      posting(entry("zero-" + UUID.randomUUID()), "sales", "CREDIT", 0);
    }));
    refused("ledger_postings_account_check", () -> transactions.run(() -> {
      posting(entry("account-" + UUID.randomUUID()), "cash", "CREDIT", 5);
    }));
    refused("ledger_postings_side_check", () -> transactions.run(() -> {
      posting(entry("side-" + UUID.randomUUID()), "sales", "BOTH", 5);
    }));
  }

  // ---------------------------------------------------------------- discrepancies

  @Test
  void a_payment_has_one_open_discrepancy_of_a_type() {
    UUID id = payment("PENDING", 1_000, 0, 0);
    discrepancy(id, "AMOUNT_CONFLICT");

    refused("discrepancies_open_per_payment", () -> discrepancy(id, "AMOUNT_CONFLICT"));
    jdbc.sql("UPDATE discrepancies SET status = 'RESOLVED', resolution = 'ACCEPTED', resolved_at = now() WHERE payment_id = ?").param(id).update();
    discrepancy(id, "AMOUNT_CONFLICT"); // a new one may open after the first was resolved
  }

  @Test
  void a_discrepancy_is_resolved_exactly_when_it_has_a_resolution() {
    UUID id = payment("PENDING", 1_000, 0, 0);
    discrepancy(id, "AMOUNT_CONFLICT");

    refused("discrepancies_resolved", () -> jdbc.sql("UPDATE discrepancies SET status = 'RESOLVED' WHERE payment_id = ?").param(id).update());
    refused("discrepancies_resolved", () -> jdbc.sql("UPDATE discrepancies SET resolution = 'ACCEPTED' WHERE payment_id = ?").param(id).update());
  }

  // ---------------------------------------------------------------- settlement

  @Test
  void a_payout_is_imported_once_and_a_line_adds_up() {
    UUID file = settlementFile("po_schema_" + UUID.randomUUID());

    refused("settlement_files_once", () -> jdbc.sql("""
        INSERT INTO settlement_files (id, gateway, payout_id, payout_date, total_net, line_count, sha256, created_at)
        SELECT gen_random_uuid(), gateway, payout_id, payout_date, 0, 0, 'other', now() FROM settlement_files WHERE id = ?
        """).param(file).update());
    refused("settlement_lines_net", () -> line(file, 1, 1_000, 30, 900, "NEW", true));
    refused("settlement_lines_waiting", () -> line(file, 2, 1_000, 30, 970, "MATCHED", true));
    refused("settlement_lines_waiting", () -> line(file, 3, 1_000, 30, 970, "NEW", false));
    refused("settlement_lines_place", () -> transactions.run(() -> { // rolled back whole: nothing is left for the matching worker
      line(file, 4, 1_000, 30, 970, "NEW", true);
      line(file, 4, 1_000, 30, 970, "NEW", true);
    }));
    refused("settlement_lines_status_check", () -> line(file, 5, 1_000, 30, 970, "PAID", false));
  }

  @Test
  void a_line_has_one_open_discrepancy_but_two_lines_of_one_payment_can_have_the_same_kind() {
    UUID payment = payment("CAPTURED", 1_000, 1_000, 0);
    UUID file = settlementFile("po_schema_" + UUID.randomUUID());
    // Lines whose net is zero: they leave the bank as it is, so the books still hold after this test.
    UUID one = line(file, 1, 30, 30, 0, "MATCHED", false);
    UUID two = line(file, 2, 30, 30, 0, "MATCHED", false);
    lineDiscrepancy(one, payment);

    refused("discrepancies_open_per_line", () -> lineDiscrepancy(one, payment));
    lineDiscrepancy(two, payment); // the same type for the same payment, on another line
  }

  // ---------------------------------------------------------------- helpers

  private UUID settlementFile(String payoutId) {
    UUID id = UUID.randomUUID();
    jdbc.sql("""
        INSERT INTO settlement_files (id, gateway, payout_id, payout_date, total_net, line_count, sha256, created_at)
        VALUES (?, 'CARD_GATEWAY', ?, current_date, 0, 0, 'h', now())
        """).param(id).param(payoutId).update();
    return id;
  }

  private UUID line(UUID file, int number, long gross, long fee, long net, String status, boolean waiting) {
    UUID id = UUID.randomUUID();
    jdbc.sql("""
        INSERT INTO settlement_lines (id, file_id, line_number, gateway, transaction_id, gateway_payment_id, type, type_text, gross, fee, net,
          occurred_at, status, next_attempt_at)
        VALUES (?, ?, ?, 'CARD_GATEWAY', ?, 'p', 'CAPTURE', 'capture', ?, ?, ?, now(), ?, CASE WHEN ? THEN now() END)
        """).param(id).param(file).param(number).param("txn_" + id).param(gross).param(fee).param(net).param(status).param(waiting).update();
    return id;
  }

  private void lineDiscrepancy(UUID line, UUID payment) {
    jdbc.sql("INSERT INTO discrepancies (id, type, line_id, payment_id, created_at) VALUES (?, 'AMOUNT_MISMATCH', ?, ?, now())")
        .param(UUID.randomUUID()).param(line).param(payment).update();
  }


  private void refused(String because, Runnable statement) {
    assertThatThrownBy(statement::run).isInstanceOf(DataAccessException.class).hasMessageContaining(because);
  }

  private UUID payment(String status, long amount, long captured, long refunded) {
    UUID id = UUID.randomUUID();
    jdbc.sql("""
        INSERT INTO payments (id, reference, amount, currency, method, gateway, capture_mode, status, captured_amount, refunded_amount, created_at, updated_at)
        VALUES (?, ?, ?, 'SAR', 'CARD', 'CARD_GATEWAY', 'AUTO', ?, ?, ?, now(), now())
        """).param(id).param(newOrder()).param(amount).param(status).param(captured).param(refunded).update();
    if (captured > 0) {
      // Keep the books whole for the checks that run after every test.
      transactions.run(() -> {
        UUID entry = entry("capture:" + id + ":" + captured, id, "CAPTURE");
        posting(entry, "gateway_receivable", "DEBIT", captured);
        posting(entry, "sales", "CREDIT", captured);
        if (refunded > 0) {
          UUID back = entry("refund:" + id + ":" + refunded, id, "REFUND");
          posting(back, "sales", "DEBIT", refunded);
          posting(back, "gateway_receivable", "CREDIT", refunded);
        }
      });
    }
    return id;
  }

  private UUID operation(UUID payment, String type, String status) {
    UUID id = UUID.randomUUID();
    jdbc.sql("""
        INSERT INTO operations (id, payment_id, type, status, origin, amount, attempts, last_attempt_at, created_at, finished_at)
        VALUES (?, ?, ?, ?, 'API', 100, 1, now(), now(), CASE WHEN ? = 'IN_FLIGHT' THEN NULL ELSE now() END)
        """).param(id).param(payment).param(type).param(status).param(status).update();
    return id;
  }

  private void discrepancy(UUID payment, String type) {
    jdbc.sql("INSERT INTO discrepancies (id, type, payment_id, created_at) VALUES (?, ?, ?, now())")
        .param(UUID.randomUUID()).param(type).param(payment).update();
  }

  private UUID balanced(String key) {
    UUID entry = entry(key);
    posting(entry, "bank", "DEBIT", 700);
    posting(entry, "suspense", "CREDIT", 700);
    // The opposite entry, so the suspense account is back at zero for other tests.
    UUID opposite = entry(key + ":undo");
    posting(opposite, "suspense", "DEBIT", 700);
    posting(opposite, "bank", "CREDIT", 700);
    return entry;
  }

  private UUID entry(String key) {
    return entry(key, null, "SUSPENSE_IN");
  }

  private UUID entry(String key, UUID payment, String kind) {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO ledger_entries (id, kind, dedupe_key, payment_id, created_at) VALUES (?, ?, ?, ?, now())")
        .param(id).param(kind).param(key).param(payment).update();
    return id;
  }

  private void posting(UUID entry, String account, String side, long amount) {
    jdbc.sql("INSERT INTO ledger_postings (entry_id, account, side, amount) VALUES (?, ?, ?, ?)")
        .param(entry).param(account).param(side).param(amount).update();
  }
}
