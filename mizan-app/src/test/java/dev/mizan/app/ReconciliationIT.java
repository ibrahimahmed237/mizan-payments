package dev.mizan.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.app.application.SettlementWriter;
import dev.mizan.app.application.model.LineRecord;
import dev.mizan.app.application.model.PaymentRecord;
import dev.mizan.app.application.port.Payments;
import dev.mizan.app.application.port.Settlements;
import dev.mizan.app.application.port.Transactions;
import dev.mizan.app.support.Api.Answer;
import dev.mizan.app.support.Books;
import dev.mizan.app.support.IntegrationTest;
import dev.mizan.core.ledger.Entries;
import dev.mizan.core.payment.Gateway;
import dev.mizan.simulator.Call;
import dev.mizan.simulator.Fault;
import dev.mizan.simulator.FileFault;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Did the gateway pay the store what Mizan says it should? Each case, with the simulator's real files. */
class ReconciliationIT extends IntegrationTest {

  @BeforeEach
  void onlyThisTestsMoney() {
    freshPayouts();
  }

  private String gatewayPaymentLineStatus(String paymentId) {
    return String.join(",", jdbc.sql("SELECT status || ' ' || type FROM settlement_lines WHERE payment_id = ? ORDER BY seq")
        .param(UUID.fromString(paymentId)).query(String.class).list());
  }

  private long settled(String paymentId, String column) {
    return Long.parseLong(column(paymentId, column));
  }

  // ---------------------------------------------------------------- the normal day

  @Test
  void a_clean_file_settles_each_payment_and_the_bank_gets_the_net() {
    String one = captured(newOrder(), 15_000);
    String two = captured(newOrder(), 20_000);
    api.post("/v1/payments/" + two + "/refunds", Map.of("amount", 5_000));
    long bankBefore = Books.balance(jdbc, "bank");
    long feesBefore = Books.balance(jdbc, "gateway_fees");
    long receivableBefore = Books.balance(jdbc, "gateway_receivable");

    Answer uploaded = upload(payout(Gateway.CARD_GATEWAY));
    match();

    assertThat(uploaded.status()).isEqualTo(201);
    String file = uploaded.text("id");
    assertThat(lines(file)).containsExactly("MATCHED CAPTURE 15000", "MATCHED CAPTURE 20000", "MATCHED REFUND -5000");
    assertThat(finance.get("/v1/settlements/" + file).json().path("complete").asBoolean()).isTrue();
    assertThat(settled(one, "settled_captured")).isEqualTo(15_000);
    assertThat(settled(two, "settled_captured")).isEqualTo(20_000);
    assertThat(settled(two, "settled_refunded")).isEqualTo(5_000);
    long fees = cardFee(15_000) + cardFee(20_000);
    assertThat(Books.balance(jdbc, "bank") - bankBefore).isEqualTo(15_000 + 20_000 - 5_000 - fees);
    assertThat(Books.balance(jdbc, "gateway_fees") - feesBefore).isEqualTo(fees);
    assertThat(Books.balance(jdbc, "gateway_receivable") - receivableBefore).isEqualTo(-30_000); // all of it is paid out now
    assertThat(discrepancies(one)).isEmpty();
    assertThat(discrepancies(two)).isEmpty();
    assertThat(entries(one)).containsExactly("CAPTURE 15000", "SETTLE " + 15_000);
  }

  @Test
  void the_answer_to_an_upload_says_how_far_matching_has_come() {
    captured(newOrder(), 15_000);

    Answer uploaded = upload(payout(Gateway.CARD_GATEWAY));

    JsonNode file = uploaded.json();
    assertThat(file.path("gateway").asString()).isEqualTo("CARD_GATEWAY");
    assertThat(file.path("payout_id").asString()).startsWith("po_card_");
    assertThat(file.path("line_count").asInt()).isEqualTo(1);
    assertThat(file.path("total_net").asLong()).isEqualTo(15_000 - cardFee(15_000));
    assertThat(file.path("lines").path("new").asLong()).isEqualTo(1);
    assertThat(file.path("complete").asBoolean()).isFalse(); // matching is the worker's job
    match();
    assertThat(finance.get("/v1/settlements/" + uploaded.text("id")).json().path("lines").path("matched").asLong()).isEqualTo(1);
    assertThat(finance.get("/v1/settlements").json().path("data").get(0).path("id").asString()).isEqualTo(uploaded.text("id"));
  }

  @Test
  void a_pay_later_file_is_matched_by_the_providers_ids() {
    String order = newOrder();
    String id = api.post("/v1/payments", payLater(order, 15_000, "MANUAL")).text("id");
    world.simulator.customerApproves(gatewayId(id));
    sync(id);
    api.post("/v1/payments/" + id + "/capture", null);

    String file = upload(payout(Gateway.PAY_LATER)).text("id");
    match();

    assertThat(lines(file)).containsExactly("MATCHED CAPTURE 15000");
    assertThat(discrepancies(id)).isEmpty(); // its fee is the pay-later table's
  }

  // ---------------------------------------------------------------- the file as a whole

  @Test
  void the_same_file_again_is_answered_as_the_first_time_and_posts_nothing() {
    captured(newOrder(), 15_000);
    String content = payout(Gateway.CARD_GATEWAY);
    Answer first = upload(content);
    match();
    long bank = Books.balance(jdbc, "bank");

    Answer again = upload(content);
    match();

    assertThat(again.status()).isEqualTo(200);
    assertThat(again.header("Idempotent-Replay")).isEqualTo("true");
    assertThat(again.text("id")).isEqualTo(first.text("id"));
    assertThat(Books.balance(jdbc, "bank")).isEqualTo(bank);
  }

  @Test
  void the_same_payout_with_other_content_is_refused() {
    captured(newOrder(), 15_000);
    String content = payout(Gateway.CARD_GATEWAY);
    upload(content);

    Answer other = upload(content.replace("\n", "\r\n"));

    assertThat(other.status()).isEqualTo(409);
    assertThat(other.code()).isEqualTo("payout_exists");
  }

  @Test
  void a_file_with_a_wrong_total_or_cut_off_is_refused_as_a_whole_and_nothing_is_saved() {
    captured(newOrder(), 15_000);
    world.simulator.planFile(FileFault.BAD_TOTAL, null);
    String badTotal = payout(Gateway.CARD_GATEWAY);
    captured(newOrder(), 15_000);
    world.simulator.planFile(FileFault.BROKEN_FILE, null);
    String broken = payout(Gateway.CARD_GATEWAY);
    long files = jdbc.sql("SELECT count(*) FROM settlement_files").query(Long.class).single();

    Answer total = upload(badTotal);
    Answer cut = upload(broken);
    Answer garbage = upload("not,a,settlement,file");
    Answer empty = upload("");

    assertThat(total.status()).isEqualTo(422);
    assertThat(total.code()).isEqualTo("payout_total_mismatch");
    assertThat(cut.status()).isEqualTo(422);
    assertThat(garbage.status()).isEqualTo(422);
    assertThat(empty.status()).isEqualTo(422);
    assertThat(jdbc.sql("SELECT count(*) FROM settlement_files").query(Long.class).single()).isEqualTo(files);
  }

  @Test
  void a_file_of_200_000_lines_is_taken_in_one_call() {
    int count = 200_000;
    String payout = "payout_large_" + UUID.randomUUID();
    StringBuilder content = new StringBuilder(count * 64);
    content.append("H,").append(payout).append(",card_gateway,SAR,2031-01-01,").append(975L * count).append(',').append(count);
    for (int i = 1; i <= count; i++) {
      content.append("\nL,txn_large_").append(i).append(",pay_large_").append(i).append(",capture,1000,25,975,2030-12-31T09:00:00Z");
    }
    long started = System.nanoTime();

    Answer uploaded = upload(content.toString());

    long seconds = (System.nanoTime() - started) / 1_000_000_000;
    try {
      assertThat(uploaded.status()).isEqualTo(201);
      assertThat(jdbc.sql("SELECT count(*) FROM settlement_lines WHERE file_id = ?")
          .param(UUID.fromString(uploaded.text("id"))).query(Long.class).single()).isEqualTo(count);
      assertThat(seconds).as("seconds to take the file (about 9 here)").isLessThan(25);
    } finally {
      // These lines name no payment of this world. They are taken away before another test's matching meets them.
      jdbc.sql("DELETE FROM settlement_lines WHERE transaction_id LIKE 'txn_large_%'").update();
      jdbc.sql("DELETE FROM settlement_files WHERE payout_id = ?").param(payout).update();
    }
  }

  @Test
  void settlements_are_for_finance_only() {
    assertThat(api.send("POST", "/v1/settlements", "H,x", null).status()).isEqualTo(403); // the store's key
    assertThat(finance.withKey(null).get("/v1/settlements").status()).isEqualTo(401);
  }

  // ---------------------------------------------------------------- what does not match

  @Test
  void a_line_for_a_payment_that_does_not_exist_is_held_in_suspense() {
    captured(newOrder(), 15_000);
    world.simulator.planFile(FileFault.EXTRA_LINE, null);
    long suspenseBefore = Books.balance(jdbc, "suspense");

    String file = upload(payout(Gateway.CARD_GATEWAY)).text("id");
    match();

    // The money reached the bank, so it is in the books. Nobody can say what it is for yet (rule R7).
    assertThat(lines(file)).containsExactly("MATCHED CAPTURE 15000", "SUSPENSE CAPTURE 5000");
    long net = 5_000 - cardFee(5_000);
    assertThat(Books.balance(jdbc, "suspense") - suspenseBefore).isEqualTo(-net);
    String type = jdbc.sql("""
        SELECT d.type || ' ' || d.suspense_amount || ' ' || coalesce(d.payment_id::text, '-')
        FROM discrepancies d JOIN settlement_lines l ON l.id = d.line_id WHERE l.file_id = ?
        """).param(UUID.fromString(file)).query(String.class).single();
    assertThat(type).isEqualTo("UNKNOWN_PAYMENT " + net + " -");
  }

  @Test
  void a_line_for_more_than_was_captured_is_held_in_suspense() {
    String order = newOrder();
    String id = captured(order, 15_000);
    world.simulator.planFile(FileFault.WRONG_AMOUNT, order);

    upload(payout(Gateway.CARD_GATEWAY));
    match();

    long net = 15_100 - cardFee(15_100);
    assertThat(discrepancies(id)).containsExactly("AMOUNT_MISMATCH OPEN 15000 15100 " + net);
    assertThat(settled(id, "settled_captured")).isZero();
    assertThat(gatewayPaymentLineStatus(id)).isEqualTo("SUSPENSE CAPTURE");
    assertThat(events(id)).containsExactly("payment.captured", "discrepancy.opened"); // the store is told
  }

  @Test
  void a_refund_line_that_the_gateway_does_not_know_either_is_held_in_suspense() {
    String id = captured(newOrder(), 15_000);
    String gatewayId = column(id, "gateway_payment_id");
    // No refund was made, at Mizan or at the gateway. The simulator never writes such a line, so the file is written here.
    String file = String.join("\n",
        "H,payout_unknown_refund_" + UUID.randomUUID() + ",card_gateway,SAR,2031-02-01,-3000,1",
        "L,txn_unknown_refund_" + UUID.randomUUID() + "," + gatewayId + ",refund,-3000,0,-3000,2031-01-31T09:00:00Z");

    upload(file);
    match();

    // Expected: no refund waiting to be settled. Actual: a refund of 30.00. The bank lost 30.00 that nobody can explain.
    assertThat(discrepancies(id)).containsExactly("UNKNOWN_REFUND OPEN 0 3000 -3000");
    assertThat(settled(id, "settled_refunded")).isZero();
  }

  @Test
  void a_line_for_less_than_was_captured_matches_and_the_rest_is_missing_after_three_days() {
    String order = newOrder();
    String id = captured(order, 15_000);
    world.simulator.planFile(FileFault.SHORT_AMOUNT, order);
    upload(payout(Gateway.CARD_GATEWAY));
    match();
    assertThat(settled(id, "settled_captured")).isEqualTo(14_900);
    assertThat(discrepancies(id)).isEmpty();

    advance(Duration.ofDays(3));
    check();

    assertThat(discrepancies(id)).containsExactly("MISSING_FROM_SETTLEMENT OPEN 100 0 0");
  }

  @Test
  void a_fee_that_is_not_the_tables_is_posted_as_it_is_and_reported() {
    String order = newOrder();
    String id = captured(order, 15_000);
    world.simulator.planFile(FileFault.WRONG_FEE, order);
    long feesBefore = Books.balance(jdbc, "gateway_fees");

    upload(payout(Gateway.CARD_GATEWAY));
    match();

    // The money is explained; only its price is not. No suspense.
    long fee = cardFee(15_000);
    assertThat(gatewayPaymentLineStatus(id)).isEqualTo("MATCHED CAPTURE");
    assertThat(discrepancies(id)).containsExactly("FEE_MISMATCH OPEN " + fee + " " + (fee + 7) + " 0");
    assertThat(Books.balance(jdbc, "gateway_fees") - feesBefore).isEqualTo(fee + 7); // the real fee
  }

  @Test
  void the_same_transaction_paid_in_two_files_is_held_in_suspense_the_second_time() {
    String order = newOrder();
    String id = captured(order, 15_000);
    upload(payout(Gateway.CARD_GATEWAY));
    match();
    world.simulator.planFile(FileFault.DUPLICATE_LINE, order);

    upload(payout(Gateway.CARD_GATEWAY));
    match();

    long net = 15_000 - cardFee(15_000);
    assertThat(gatewayPaymentLineStatus(id)).isEqualTo("MATCHED CAPTURE,SUSPENSE CAPTURE");
    assertThat(discrepancies(id)).containsExactly("DUPLICATE_SETTLEMENT OPEN - 15000 " + net);
    assertThat(settled(id, "settled_captured")).isEqualTo(15_000); // settled once
  }

  @Test
  void when_both_files_wait_together_the_earlier_line_is_the_one_that_counts() {
    String order = newOrder();
    String id = captured(order, 15_000);
    String first = payout(Gateway.CARD_GATEWAY);
    world.simulator.planFile(FileFault.DUPLICATE_LINE, order);
    String second = payout(Gateway.CARD_GATEWAY);

    upload(first);
    upload(second); // both wait: neither line is matched yet
    match();

    assertThat(gatewayPaymentLineStatus(id)).isEqualTo("MATCHED CAPTURE,SUSPENSE CAPTURE");
    assertThat(discrepancies(id)).hasSize(1).allMatch(d -> d.startsWith("DUPLICATE_SETTLEMENT OPEN"));
  }

  @Test
  void a_line_of_a_type_mizan_does_not_know_is_held_in_suspense() {
    String order = newOrder();
    String id = captured(order, 15_000);
    upload(payout(Gateway.CARD_GATEWAY));
    match();
    world.simulator.planFile(FileFault.CHARGEBACK_LINE, order);

    upload(payout(Gateway.CARD_GATEWAY));
    match();

    // Money taken out of the payout: the suspense account holds a negative amount, on the debit side.
    assertThat(gatewayPaymentLineStatus(id)).isEqualTo("MATCHED CAPTURE,SUSPENSE OTHER");
    assertThat(discrepancies(id)).containsExactly("UNKNOWN_LINE_TYPE OPEN - -15000 -15000");
  }

  // ---------------------------------------------------------------- the sync before giving up

  @Test
  void a_capture_that_mizan_holds_as_failed_is_found_by_the_sync_before_the_line_gives_up() {
    String order = newOrder();
    world.simulator.plan(order, Call.CREATE, Fault.VERY_SLOW_APPLY); // the gateway charges two minutes later
    String id = pay(order, 15_000, "1111", "AUTO").text("id");
    advance(Duration.ofMinutes(1));
    sync(id);
    assertThat(payment(id).text("status")).isEqualTo("FAILED"); // the store was told so
    advance(Duration.ofMinutes(1)); // the charge happens; no webhook reaches Mizan, and nobody looks

    upload(payout(Gateway.CARD_GATEWAY));
    match();

    assertThat(payment(id).text("status")).isEqualTo("CAPTURED");
    assertThat(payment(id).json().path("late").asBoolean()).isTrue();
    assertThat(gatewayPaymentLineStatus(id)).isEqualTo("MATCHED CAPTURE");
    assertThat(discrepancies(id)).isEmpty();
    assertThat(column(id, "follow_up")).isEqualTo("AUTO_REFUND"); // the sync worker returns it next
  }

  @Test
  void a_refund_made_in_the_gateways_dashboard_is_found_by_the_sync_before_the_line_gives_up() {
    String id = captured(newOrder(), 15_000);
    world.simulator.externalRefund(id, 4_000);

    upload(payout(Gateway.CARD_GATEWAY));
    match();

    assertThat(payment(id).number("refunded_amount")).isEqualTo(4_000);
    assertThat(gatewayPaymentLineStatus(id)).isEqualTo("MATCHED CAPTURE,MATCHED REFUND");
    assertThat(discrepancies(id)).isEmpty();
    assertThat(operations(id)).contains("REFUND SUCCEEDED GATEWAY 4000");
  }

  @Test
  void a_line_whose_payment_cannot_be_synced_waits_and_is_tried_again() {
    String order = newOrder();
    String id = captured(order, 15_000);
    world.simulator.externalRefund(id, 4_000);
    world.simulator.fetchDown(order, 1);

    upload(payout(Gateway.CARD_GATEWAY));
    match();
    assertThat(gatewayPaymentLineStatus(id)).isEqualTo("MATCHED CAPTURE,NEEDS_SYNC REFUND");

    advance(Duration.ofSeconds(30));
    match();

    assertThat(gatewayPaymentLineStatus(id)).isEqualTo("MATCHED CAPTURE,MATCHED REFUND");
  }

  // ---------------------------------------------------------------- finance closes a discrepancy

  @Test
  void a_line_that_fits_after_the_gateway_fixed_its_record_is_matched_again_by_a_retry() {
    String order = newOrder();
    world.simulator.plan(order, Call.CREATE, Fault.VERY_SLOW_APPLY);
    String id = pay(order, 15_000, "1111", "AUTO").text("id");
    advance(Duration.ofMinutes(1));
    sync(id); // failed
    world.simulator.stick(order, Fault.AMOUNT_CHANGED); // for a while, the gateway's record shows another amount
    advance(Duration.ofMinutes(1)); // the charge happens
    upload(payout(Gateway.CARD_GATEWAY));
    match();
    long net = 15_000 - cardFee(15_000);
    String mismatch = jdbc.sql("SELECT id::text FROM discrepancies WHERE payment_id = ? AND type = 'NOT_CAPTURED_HERE'")
        .param(UUID.fromString(id)).query(String.class).single();
    long suspense = Books.balance(jdbc, "suspense");

    Answer still = finance.post("/v1/discrepancies/" + mismatch + "/retry", null);
    world.simulator.unstick(order, Fault.AMOUNT_CHANGED); // the gateway fixed its record
    Answer retried = finance.post("/v1/discrepancies/" + mismatch + "/retry", null);

    assertThat(still.status()).isEqualTo(409);
    assertThat(still.code()).isEqualTo("still_unmatched");
    assertThat(retried.status()).isEqualTo(200);
    assertThat(retried.text("status")).isEqualTo("RESOLVED");
    assertThat(retried.text("resolution")).isEqualTo("REMATCHED");
    assertThat(gatewayPaymentLineStatus(id)).isEqualTo("MATCHED CAPTURE");
    assertThat(settled(id, "settled_captured")).isEqualTo(15_000);
    assertThat(Books.balance(jdbc, "suspense") - suspense).isEqualTo(net); // the money left suspense
    assertThat(entries(id)).contains("REMATCH " + 15_000);
  }

  @Test
  void accepting_a_difference_moves_its_money_from_suspense_to_adjustments() {
    captured(newOrder(), 15_000);
    world.simulator.planFile(FileFault.EXTRA_LINE, null);
    String file = upload(payout(Gateway.CARD_GATEWAY)).text("id");
    match();
    String ghost = jdbc.sql("SELECT d.id::text FROM discrepancies d JOIN settlement_lines l ON l.id = d.line_id WHERE l.file_id = ?")
        .param(UUID.fromString(file)).query(String.class).single();
    long suspense = Books.balance(jdbc, "suspense");
    long adjustments = Books.balance(jdbc, "adjustments");

    Answer noNote = finance.post("/v1/discrepancies/" + ghost + "/accept", Map.of());
    Answer accepted = finance.post("/v1/discrepancies/" + ghost + "/accept", Map.of("note", "Paid by mistake; the gateway confirmed by email."));
    Answer twice = finance.post("/v1/discrepancies/" + ghost + "/accept", Map.of("note", "again"));

    long net = 5_000 - cardFee(5_000);
    assertThat(noNote.status()).isEqualTo(422);
    assertThat(noNote.code()).isEqualTo("note_required");
    assertThat(accepted.status()).isEqualTo(200);
    assertThat(accepted.text("resolution")).isEqualTo("ACCEPTED");
    assertThat(accepted.text("note")).isEqualTo("Paid by mistake; the gateway confirmed by email.");
    assertThat(twice.code()).isEqualTo("already_resolved");
    assertThat(Books.balance(jdbc, "suspense") - suspense).isEqualTo(net);
    assertThat(Books.balance(jdbc, "adjustments") - adjustments).isEqualTo(-net);
    assertThat(lines(file)).containsExactly("MATCHED CAPTURE 15000", "ACCEPTED CAPTURE 5000");
  }

  @Test
  void a_difference_without_money_is_accepted_without_an_entry() {
    String order = newOrder();
    String id = captured(order, 15_000);
    world.simulator.planFile(FileFault.WRONG_FEE, order);
    upload(payout(Gateway.CARD_GATEWAY));
    match();
    String fee = jdbc.sql("SELECT id::text FROM discrepancies WHERE payment_id = ?").param(UUID.fromString(id)).query(String.class).single();
    long entries = jdbc.sql("SELECT count(*) FROM ledger_entries").query(Long.class).single();

    Answer accepted = finance.post("/v1/discrepancies/" + fee + "/accept", Map.of("note", "New fee from November."));

    assertThat(accepted.text("status")).isEqualTo("RESOLVED");
    assertThat(jdbc.sql("SELECT count(*) FROM ledger_entries").query(Long.class).single()).isEqualTo(entries);
    assertThat(gatewayPaymentLineStatus(id)).isEqualTo("MATCHED CAPTURE"); // the line stays matched
  }

  @Test
  void only_an_unmatched_line_can_be_retried() {
    String order = newOrder();
    String id = captured(order, 15_000);
    upload(payout(Gateway.CARD_GATEWAY));
    match();
    world.simulator.planFile(FileFault.DUPLICATE_LINE, order);
    upload(payout(Gateway.CARD_GATEWAY));
    match();
    String duplicate = jdbc.sql("SELECT id::text FROM discrepancies WHERE payment_id = ?").param(UUID.fromString(id)).query(String.class).single();

    Answer retried = finance.post("/v1/discrepancies/" + duplicate + "/retry", null);

    assertThat(retried.status()).isEqualTo(409);
    assertThat(retried.code()).isEqualTo("not_retryable");
    assertThat(finance.post("/v1/discrepancies/" + UUID.randomUUID() + "/retry", null).status()).isEqualTo(404);
    assertThat(finance.post("/v1/discrepancies/" + UUID.randomUUID() + "/accept", Map.of("note", "x")).status()).isEqualTo(404);
  }

  // ---------------------------------------------------------------- the daily check

  @Test
  void a_capture_that_no_file_paid_out_is_missing_after_three_days_and_closes_when_a_file_comes() {
    String id = captured(newOrder(), 15_000);
    String late = payout(Gateway.CARD_GATEWAY); // the gateway's file exists, but finance has not uploaded it

    advance(Duration.ofDays(3).minusSeconds(1));
    check();
    assertThat(discrepancies(id)).isEmpty();
    advance(Duration.ofSeconds(1));
    check();
    check(); // once per payment, however often it runs
    assertThat(discrepancies(id)).containsExactly("MISSING_FROM_SETTLEMENT OPEN 15000 0 0");

    upload(late);
    match();

    assertThat(discrepancies(id)).containsExactly("MISSING_FROM_SETTLEMENT RESOLVED 15000 0 0");
    assertThat(jdbc.sql("SELECT resolution FROM discrepancies WHERE payment_id = ?").param(UUID.fromString(id)).query(String.class).single())
        .isEqualTo("SETTLED_LATER");
  }

  @Test
  void a_missing_line_is_reported_by_the_daily_check() {
    String order = newOrder();
    String id = captured(order, 15_000);
    world.simulator.planFile(FileFault.MISSING_LINE, order);
    upload(payout(Gateway.CARD_GATEWAY));
    match();
    assertThat(discrepancies(id)).isEmpty();

    advance(Duration.ofDays(3));
    check();

    assertThat(discrepancies(id)).containsExactly("MISSING_FROM_SETTLEMENT OPEN 15000 0 0");
  }

  @Test
  void a_refund_that_no_file_took_back_is_missing_too() {
    String id = captured(newOrder(), 15_000);
    upload(payout(Gateway.CARD_GATEWAY));
    match();
    api.post("/v1/payments/" + id + "/refunds", Map.of("amount", 4_000));

    advance(Duration.ofDays(3));
    check();

    assertThat(discrepancies(id)).containsExactly("MISSING_FROM_SETTLEMENT OPEN 4000 0 0");
  }

  @Test
  void a_file_that_settles_only_part_of_what_is_missing_leaves_it_open() {
    String order = newOrder();
    String id = captured(order, 15_000);
    api.post("/v1/payments/" + id + "/refunds", Map.of("amount", 4_000));
    world.simulator.planFile(FileFault.MISSING_LINE, order); // the file will have the refund, not the capture
    String file = payout(Gateway.CARD_GATEWAY);
    advance(Duration.ofDays(3));
    check();
    assertThat(discrepancies(id)).containsExactly("MISSING_FROM_SETTLEMENT OPEN 19000 0 0"); // both are missing

    upload(file);
    match();

    assertThat(settled(id, "settled_refunded")).isEqualTo(4_000);
    assertThat(discrepancies(id)).containsExactly("MISSING_FROM_SETTLEMENT OPEN 19000 0 0"); // the capture still is
  }

  @Test
  void a_line_matched_while_the_daily_check_runs_is_seen_by_the_check() throws Exception {
    String id = captured(newOrder(), 15_000);
    String file = upload(payout(Gateway.CARD_GATEWAY)).text("id"); // uploaded, not matched yet
    UUID line = jdbc.sql("SELECT id FROM settlement_lines WHERE file_id = ?").param(UUID.fromString(file)).query(UUID.class).single();
    advance(Duration.ofDays(3)); // the capture is old enough to be missing
    var transactions = world.app.getBean(Transactions.class);
    var settlements = world.app.getBean(Settlements.class);
    var payments = world.app.getBean(Payments.class);
    var writer = world.app.getBean(SettlementWriter.class);
    var check = world.app.getBean(dev.mizan.app.application.CheckMissingSettlements.class);
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService threads = Executors.newFixedThreadPool(2);
    try {
      // A matching worker holds the line and the payment, as it does while it matches.
      Future<?> matching = threads.submit(() -> transactions.run(() -> {
        LineRecord held = settlements.lockLine(line).orElseThrow();
        PaymentRecord payment = payments.lock(UUID.fromString(id)).orElseThrow();
        locked.countDown();
        await(go);
        writer.matched(held, payment, Entries.settle(held.id(), held.line().gross(), held.line().fee()), now());
      }));
      locked.await(10, TimeUnit.SECONDS);
      // The daily check starts. It must wait for the payment, not decide on what it read before.
      Future<Integer> checked = threads.submit(check::handle);
      await(() -> checked.isDone() || waitsForALock());
      go.countDown();
      matching.get(30, TimeUnit.SECONDS);

      assertThat(checked.get(30, TimeUnit.SECONDS)).isZero();
      assertThat(discrepancies(id)).isEmpty(); // no false "missing", and no event about one
    } finally {
      go.countDown();
      threads.shutdownNow();
    }
  }

  private boolean waitsForALock() {
    return jdbc.sql("SELECT count(*) FROM pg_locks WHERE NOT granted").query(Long.class).single() > 0;
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void check() {
    world.app.getBean(dev.mizan.app.application.CheckMissingSettlements.class).handle();
  }

  // ---------------------------------------------------------------- the books

  @Test
  void the_balances_the_entries_of_a_payment_and_the_report_are_readable_by_finance() {
    String id = captured(newOrder(), 15_000);
    world.simulator.planFile(FileFault.EXTRA_LINE, null);
    upload(payout(Gateway.CARD_GATEWAY));
    match();

    JsonNode balances = finance.get("/v1/ledger/balances").json();
    JsonNode entries = finance.get("/v1/ledger/entries?payment_id=" + id).json().path("data");
    JsonNode report = finance.get("/v1/reports/reconciliation").json();

    assertThat(balances.path("balanced").asBoolean()).isTrue();
    assertThat(balances.path("total_debits").asLong()).isEqualTo(balances.path("total_credits").asLong());
    assertThat(balances.path("accounts")).hasSize(6);
    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).path("kind").asString()).isEqualTo("CAPTURE");
    assertThat(entries.get(1).path("kind").asString()).isEqualTo("SETTLE");
    assertThat(entries.get(1).path("postings")).hasSize(3); // bank, fees, receivable
    assertThat(report.path("suspense").asLong()).isEqualTo(-Books.balance(jdbc, "suspense"));
    assertThat(report.path("unsettled").has("up_to_3_days")).isTrue();
    assertThat(List.of(report.path("open_discrepancies").toString())).anyMatch(text -> text.contains("UNKNOWN_PAYMENT"));
    assertThat(api.get("/v1/ledger/balances").status()).isEqualTo(403); // not for the store's key
    assertThat(finance.get("/v1/ledger/entries?payment_id=" + UUID.randomUUID()).status()).isEqualTo(404);
  }

  @Test
  void a_long_list_of_discrepancies_comes_in_pages_and_each_one_comes_once() {
    List<String> payments = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      String order = newOrder();
      payments.add(captured(order, 15_000));
      world.simulator.planFile(FileFault.WRONG_FEE, order);
    }
    upload(payout(Gateway.CARD_GATEWAY));
    match();

    List<String> seen = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      JsonNode page = finance.get("/v1/discrepancies?status=OPEN&type=FEE_MISMATCH&limit=2" + (cursor == null ? "" : "&cursor=" + cursor)).json();
      assertThat(page.path("data").size()).isLessThanOrEqualTo(2);
      page.path("data").forEach(one -> seen.add(one.path("payment_id").asString()));
      cursor = page.path("next_cursor").asString(null);
      pages++;
    } while (cursor != null && pages < 1_000);

    // Earlier tests left theirs too; these five are the newest, so they come first, the last one opened first.
    assertThat(pages).isGreaterThanOrEqualTo(3);
    assertThat(seen).doesNotHaveDuplicates();
    assertThat(seen.subList(0, 5)).containsExactly(payments.get(4), payments.get(3), payments.get(2), payments.get(1), payments.get(0));
    assertThat(finance.get("/v1/discrepancies?cursor=not-a-cursor").code()).isEqualTo("invalid_cursor");
  }

  @Test
  void the_report_shows_unsettled_money_by_the_age_of_its_capture() {
    advance(Duration.ofDays(8)); // every earlier test's unsettled money is now older than 7 days
    String id = captured(newOrder(), 15_000);
    api.post("/v1/payments/" + id + "/refunds", Map.of("amount", 4_000));

    JsonNode fresh = finance.get("/v1/reports/reconciliation").json().path("unsettled");
    advance(Duration.ofDays(3));
    JsonNode aging = finance.get("/v1/reports/reconciliation").json().path("unsettled");
    advance(Duration.ofDays(4));
    JsonNode old = finance.get("/v1/reports/reconciliation").json().path("unsettled");

    // Captured 150.00, refunded 40.00, nothing settled: the gateway still owes the store 110.00.
    assertThat(fresh.path("up_to_3_days").asLong()).isEqualTo(11_000);
    assertThat(fresh.path("3_to_7_days").asLong()).isZero();
    assertThat(aging.path("up_to_3_days").asLong()).isZero();
    assertThat(aging.path("3_to_7_days").asLong()).isEqualTo(11_000);
    assertThat(old.path("3_to_7_days").asLong()).isZero();
    assertThat(old.path("over_7_days").asLong() - aging.path("over_7_days").asLong()).isEqualTo(11_000);
  }

  @Test
  void the_report_counts_open_discrepancies_by_type_with_the_money_they_hold() {
    JsonNode before = finance.get("/v1/reports/reconciliation").json().path("open_discrepancies");
    captured(newOrder(), 15_000);
    world.simulator.planFile(FileFault.EXTRA_LINE, null); // 50.00 for a payment that does not exist
    String file = upload(payout(Gateway.CARD_GATEWAY)).text("id");
    match();

    JsonNode after = finance.get("/v1/reports/reconciliation").json().path("open_discrepancies");
    String discrepancy = jdbc.sql("SELECT d.id::text FROM discrepancies d JOIN settlement_lines l ON l.id = d.line_id WHERE l.file_id = ?")
        .param(UUID.fromString(file)).query(String.class).single();
    finance.post("/v1/discrepancies/" + discrepancy + "/accept", Map.of("note", "A test payment made at the gateway by hand."));
    JsonNode closed = finance.get("/v1/reports/reconciliation").json().path("open_discrepancies");

    assertThat(ofType(after, "UNKNOWN_PAYMENT").path("count").asLong() - ofType(before, "UNKNOWN_PAYMENT").path("count").asLong()).isEqualTo(1);
    assertThat(ofType(after, "UNKNOWN_PAYMENT").path("suspense_amount").asLong() - ofType(before, "UNKNOWN_PAYMENT").path("suspense_amount").asLong())
        .isEqualTo(5_000 - cardFee(5_000));
    // Only open ones are counted: once finance has accepted it, it is gone from the report.
    assertThat(ofType(closed, "UNKNOWN_PAYMENT").path("count").asLong()).isEqualTo(ofType(before, "UNKNOWN_PAYMENT").path("count").asLong());
  }

  /** The report's row for one type of discrepancy; an empty object when there is none. */
  private static JsonNode ofType(JsonNode openDiscrepancies, String type) {
    for (JsonNode row : openDiscrepancies) {
      if (row.path("type").asString().equals(type)) {
        return row;
      }
    }
    return dev.mizan.app.support.Api.JSON.createObjectNode();
  }

  @Test
  void discrepancies_are_listed_by_status_and_type() {
    String order = newOrder();
    String id = captured(order, 15_000);
    world.simulator.planFile(FileFault.WRONG_FEE, order);
    upload(payout(Gateway.CARD_GATEWAY));
    match();

    JsonNode open = finance.get("/v1/discrepancies?status=OPEN&type=FEE_MISMATCH").json().path("data");
    JsonNode one = open.get(0);

    assertThat(one.path("type").asString()).isEqualTo("FEE_MISMATCH");
    assertThat(one.path("payment_id").asString()).isEqualTo(id);
    assertThat(one.path("line_id").asString()).isNotBlank();
    assertThat(finance.get("/v1/discrepancies/" + one.path("id").asString()).text("status")).isEqualTo("OPEN");
    assertThat(finance.get("/v1/discrepancies?status=MAYBE").code()).isEqualTo("invalid_status");
  }
}
