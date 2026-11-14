package dev.mizan.app.proof;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.app.application.CheckMissingSettlements;
import dev.mizan.app.application.RunDueEvents;
import dev.mizan.app.application.RunDuePayments;
import dev.mizan.app.support.Api.Answer;
import dev.mizan.app.support.Books;
import dev.mizan.app.support.IntegrationTest;
import dev.mizan.core.payment.Gateway;
import dev.mizan.core.payment.GatewayStatus;
import dev.mizan.simulator.Call;
import dev.mizan.simulator.Fault;
import dev.mizan.simulator.FileFault;
import dev.mizan.simulator.SimPayment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The proof run: many payments, each with faults drawn from a seed, through the whole life of a payment and its
 * settlement. At the end, Mizan's record is compared with the simulator's own book, which is what really happened
 * at the gateways. See docs/DESIGN.md, "Testing and proof".
 *
 * <p>The same seed gives the same plan. {@code -Dmizan.proof.payments=2000 -Dmizan.proof.seed=7} runs a longer one, and
 * {@code -Dmizan.test.parallel=8} runs the workers' rounds 8 at a time, as the service does by default.
 */
class ProofRunIT extends IntegrationTest {

  private static final int PAYMENTS = Integer.getInteger("mizan.proof.payments", 150);
  private static final long SEED = Long.getLong("mizan.proof.seed", 20261114L);

  private static final List<Fault> CREATE_FAULTS = List.of(
      Fault.LOST_ANSWER, Fault.TIMEOUT_BEFORE, Fault.SLOW_APPLY, Fault.VERY_SLOW_APPLY, Fault.ERROR_500, Fault.ERROR_500_AFTER, Fault.GARBAGE);
  private static final List<Fault> CALL_FAULTS = List.of(
      Fault.LOST_ANSWER, Fault.TIMEOUT_BEFORE, Fault.ERROR_500, Fault.ERROR_500_AFTER, Fault.VERY_SLOW_APPLY, Fault.GARBAGE);
  private static final List<Fault> WEBHOOK_FAULTS = List.of(
      Fault.WEBHOOK_DROP, Fault.WEBHOOK_DUP, Fault.WEBHOOK_LATE, Fault.WEBHOOK_REORDER, Fault.WEBHOOK_EARLY, Fault.WEBHOOK_FORGED);

  /** What the customer does on the bank's or the provider's page. */
  private enum Customer { APPROVES, DECLINES, LEAVES, APPROVES_LATE }

  /** One planned payment and what happened to it. */
  private static final class Plan {
    final String order;
    final boolean payLater;
    final String card;
    final boolean manual;
    final long amount;
    Customer customer;
    final List<String> ids = new ArrayList<>();
    final List<String> faults = new ArrayList<>();
    boolean twoTabs;

    Plan(String order, boolean payLater, String card, boolean manual, long amount) {
      this.order = order;
      this.payLater = payLater;
      this.card = card;
      this.manual = manual;
      this.amount = amount;
    }

    boolean simple() {
      return ids.size() == 1 && !twoTabs && customer != Customer.APPROVES_LATE;
    }
  }

  private final Random random = new Random(SEED);
  private final Map<String, Integer> faultCounts = new TreeMap<>();
  private final List<String> report = new ArrayList<>();

  @Test
  void mizans_record_equals_what_really_happened_at_the_gateways() throws IOException {
    long started = System.nanoTime();
    freshPayouts(); // the payouts of this run hold only this run's money
    List<Plan> plans = new ArrayList<>();
    for (int i = 0; i < PAYMENTS; i++) {
      plans.add(plan(i));
    }

    // ---------------------------------------------------------------- the payments' lives
    for (Plan plan : plans) {
      create(plan);
    }
    for (Plan plan : plans) {
      customerActs(plan, false);
    }
    settleDown(Duration.ofMinutes(1), 3);
    for (Plan plan : plans) {
      storeActs(plan);
    }
    settleDown(Duration.ofMinutes(1), 3);
    for (Plan plan : plans) {
      storeRefunds(plan);
    }
    settleDown(Duration.ofMinutes(1), 3);
    advance(Duration.ofMinutes(31)); // the waiting time of card payments is over
    rounds();
    for (Plan plan : plans) {
      customerActs(plan, true);
    }
    settleDown(Duration.ofMinutes(1), 3);
    settleDown(Duration.ofDays(1), 8); // holds that nobody captured end, and are released
    settleDown(Duration.ofMinutes(1), 3);

    // ---------------------------------------------------------------- the settlement
    Map<String, Set<String>> expected = new HashMap<>();
    List<Plan> settledOnce = plantFirstPayoutFaults(plans, expected);
    Answer cardFile = upload(payout(Gateway.CARD_GATEWAY));
    Answer payLaterFile = upload(payout(Gateway.PAY_LATER));
    match();
    plantSecondPayoutFaults(settledOnce, expected);
    Answer secondCardFile = upload(payout(Gateway.CARD_GATEWAY));
    world.simulator.planFile(FileFault.BAD_TOTAL, null);
    captured(newOrder("proof-bad-total"), 10_000); // something for the broken payout to hold
    Answer refused = upload(payout(Gateway.CARD_GATEWAY));
    match();
    advance(Duration.ofDays(3));
    world.app.getBean(CheckMissingSettlements.class).handle();
    rounds(); // the late captures that the settlement found are returned, and every event is sent

    // ---------------------------------------------------------------- the checks
    List<String> fileIds = List.of(cardFile.text("id"), payLaterFile.text("id"), secondCardFile.text("id"));
    assertThat(refused.code()).as("a payout with a wrong total").isEqualTo("payout_total_mismatch");
    int compared = compareWithTheBook(plans);
    nobodyPaidTwice(plans);
    everythingIsDone(plans);
    Books.check(jdbc);
    Books.checkSettlements(jdbc);
    int reported = everyPlantedErrorIsReportedAndNothingElse(plans, expected, fileIds);

    writeReport(plans, compared, reported, expected, Duration.ofNanos(System.nanoTime() - started));
  }

  // ---------------------------------------------------------------- planning

  private Plan plan(int i) {
    boolean payLater = random.nextInt(100) < 30;
    String card = payLater ? null : pick(List.of("1111", "1111", "1111", "1111", "1111", "1111", "1111", "3333", "3333", "0002"));
    boolean manual = random.nextInt(100) < 30;
    long amount = 1_000 + random.nextInt(50_000);
    Plan plan = new Plan("proof-" + SEED + "-" + i + "-" + UUID.randomUUID().toString().substring(0, 6), payLater, card, manual, amount);
    int customer = random.nextInt(100);
    plan.customer = customer < 70 ? Customer.APPROVES : customer < 82 ? Customer.DECLINES : customer < 92 ? Customer.LEAVES : Customer.APPROVES_LATE;
    plan.twoTabs = !payLater && "3333".equals(card) && random.nextInt(100) < 25;
    world.webhooksFor(plan.order);
    if (random.nextInt(100) < 15) {
      fault(plan, Call.CREATE, pick(CREATE_FAULTS));
    }
    if (random.nextInt(100) < 25) {
      Fault sticky = pick(WEBHOOK_FAULTS);
      world.simulator.stick(plan.order, sticky);
      count(plan, sticky.name());
    }
    return plan;
  }

  private void create(Plan plan) {
    Map<String, Object> body = plan.payLater
        ? payLater(plan.order, plan.amount, plan.manual ? "MANUAL" : "AUTO")
        : card(plan.order, plan.amount, plan.card, plan.manual ? "MANUAL" : "AUTO");
    Answer created = api.post("/v1/payments", body);
    assertThat(created.status()).as("create of %s", plan.order).isEqualTo(201);
    plan.ids.add(created.text("id"));
    if (plan.twoTabs) {
      Answer second = api.post("/v1/payments", card(plan.order, plan.amount, plan.card, plan.manual ? "MANUAL" : "AUTO"));
      if (second.status() == 201) { // refused when the first one's result is not known: that is the rule
        plan.ids.add(second.text("id"));
        count(plan, "TWO_TABS");
      }
    }
  }

  private void customerActs(Plan plan, boolean late) {
    if ((plan.customer == Customer.APPROVES_LATE) != late || !(plan.payLater || "3333".equals(plan.card))) {
      return;
    }
    for (String id : plan.ids) {
      String atGateway = gatewayId(id);
      SimPayment sim = atGateway == null ? null : atGateway(atGateway);
      if (sim == null || sim.status() != GatewayStatus.INITIATED) {
        continue; // never reached the gateway, or nothing left for the customer to do
      }
      switch (plan.customer) {
        case APPROVES, APPROVES_LATE -> world.simulator.customerApproves(atGateway);
        case DECLINES -> world.simulator.customerDeclines(atGateway);
        case LEAVES -> world.simulator.customerLeaves(atGateway);
      }
    }
  }

  /** The store captures or cancels a hold, sometimes with a fault on the call. */
  private void storeActs(Plan plan) {
    for (String id : plan.ids) {
      if (!"AUTHORIZED".equals(payment(id).text("status"))) {
        continue;
      }
      int choice = random.nextInt(100);
      if (choice < 10) {
        continue; // nobody captures: the hold will end by itself
      }
      boolean capture = choice < 70;
      if (plan.simple() && random.nextInt(100) < 15) {
        fault(plan, capture ? Call.CAPTURE : Call.VOID, pick(CALL_FAULTS));
      }
      if (capture) {
        boolean part = !plan.payLater && random.nextInt(100) < 25;
        Map<String, Object> body = part ? Map.of("amount", plan.amount - 1 - random.nextInt((int) (plan.amount / 2))) : Map.of();
        api.post("/v1/payments/" + id + "/capture", body);
      } else {
        api.post("/v1/payments/" + id + "/cancel", null);
      }
    }
  }

  /** The store refunds some captured payments; someone refunds a few in the gateway's dashboard. */
  private void storeRefunds(Plan plan) {
    if (!plan.simple()) {
      return; // a payment with a follow-up of its own is left to it
    }
    String id = plan.ids.get(0);
    Answer payment = payment(id);
    long left = payment.number("captured_amount") - payment.number("refunded_amount");
    if (!"CAPTURED".equals(payment.text("status")) || left <= 0) {
      return;
    }
    int choice = random.nextInt(100);
    if (choice < 25) {
      if (random.nextInt(100) < 20) {
        fault(plan, Call.REFUND, pick(CALL_FAULTS));
      }
      long amount = random.nextBoolean() ? left : 1 + random.nextInt((int) left);
      api.post("/v1/payments/" + id + "/refunds", Map.of("amount", amount));
    } else if (choice < 29) {
      world.simulator.externalRefund(gatewayId(id), 1 + random.nextInt((int) left));
      count(plan, "DASHBOARD_REFUND");
    }
  }

  // ---------------------------------------------------------------- settlement faults

  private List<Plan> plantFirstPayoutFaults(List<Plan> plans, Map<String, Set<String>> expected) {
    List<Plan> candidates = plans.stream()
        .filter(plan -> plan.simple() && !plan.payLater && atGateway(plan.ids.get(0)) != null && atGateway(plan.ids.get(0)).captured() > 0)
        .collect(Collectors.toCollection(ArrayList::new));
    Collections.shuffle(candidates, random);
    int each = Math.max(1, candidates.size() / 25);
    int next = 0;
    for (FileFault fault : List.of(FileFault.WRONG_AMOUNT, FileFault.SHORT_AMOUNT, FileFault.WRONG_FEE, FileFault.MISSING_LINE)) {
      for (int i = 0; i < each && next < candidates.size(); i++) {
        Plan plan = candidates.get(next++);
        world.simulator.planFile(fault, plan.order);
        Set<String> types = expected.computeIfAbsent(plan.ids.get(0), k -> new TreeSet<>());
        switch (fault) {
          case WRONG_AMOUNT -> {
            types.add("AMOUNT_MISMATCH");
            types.add("MISSING_FROM_SETTLEMENT"); // its capture is in suspense, so it is not settled either
          }
          case SHORT_AMOUNT, MISSING_LINE -> types.add("MISSING_FROM_SETTLEMENT");
          case WRONG_FEE -> types.add("FEE_MISMATCH");
          default -> throw new IllegalStateException();
        }
        faultCounts.merge("file: " + fault.name(), 1, Integer::sum);
      }
    }
    world.simulator.planFile(FileFault.EXTRA_LINE, null);
    faultCounts.merge("file: EXTRA_LINE", 1, Integer::sum);
    return candidates.subList(next, candidates.size());
  }

  private void plantSecondPayoutFaults(List<Plan> settledOnce, Map<String, Set<String>> expected) {
    int each = Math.max(1, settledOnce.size() / 25);
    int next = 0;
    for (FileFault fault : List.of(FileFault.DUPLICATE_LINE, FileFault.CHARGEBACK_LINE)) {
      for (int i = 0; i < each && next < settledOnce.size(); i++) {
        Plan plan = settledOnce.get(next++);
        world.simulator.planFile(fault, plan.order);
        expected.computeIfAbsent(plan.ids.get(0), k -> new TreeSet<>()).add(fault == FileFault.DUPLICATE_LINE ? "DUPLICATE_SETTLEMENT" : "UNKNOWN_LINE_TYPE");
        faultCounts.merge("file: " + fault.name(), 1, Integer::sum);
      }
    }
  }

  // ---------------------------------------------------------------- the checks

  /** For every payment: what Mizan holds equals what the gateway holds. */
  private int compareWithTheBook(List<Plan> plans) {
    int compared = 0;
    for (Plan plan : plans) {
      for (String id : plan.ids) {
        Answer mizan = payment(id);
        SimPayment sim = world.simulator.payments().stream().filter(p -> id.equals(p.mizanPaymentId)).findFirst().orElse(null);
        long captured = sim == null ? 0 : sim.captured();
        long refunded = sim == null ? 0 : sim.refunded();
        assertThat(mizan.number("captured_amount")).as("captured, %s %s %s", plan.order, id, plan.faults).isEqualTo(captured);
        assertThat(mizan.number("refunded_amount")).as("refunded, %s %s %s", plan.order, id, plan.faults).isEqualTo(refunded);
        assertThat("CAPTURED".equals(mizan.text("status"))).as("captured status, %s %s", plan.order, plan.faults).isEqualTo(captured > 0);
        compared++;
      }
    }
    return compared;
  }

  /** No card was charged twice, and an order keeps the money of one payment at most. */
  private void nobodyPaidTwice(List<Plan> plans) {
    for (Plan plan : plans) {
      List<SimPayment> sims = world.simulator.paymentsOf(plan.order);
      for (SimPayment sim : sims) {
        assertThat(sim.charges()).as("charges of %s %s", plan.order, plan.faults).isLessThanOrEqualTo(1);
      }
      long keepingMoney = sims.stream().filter(sim -> sim.captured() - sim.refunded() > 0).count();
      assertThat(keepingMoney).as("payments of %s that keep money %s", plan.order, plan.faults).isLessThanOrEqualTo(1);
      for (String id : plan.ids) {
        Answer payment = payment(id);
        if (payment.json().path("late").asBoolean()) { // the store was told it had ended: the money went back
          assertThat(payment.number("refunded_amount")).as("late capture of %s returned %s", plan.order, plan.faults)
              .isEqualTo(payment.number("captured_amount"));
        }
      }
    }
  }

  /** Nothing is left half done: no operation in flight, no payment waiting, every event delivered. */
  private void everythingIsDone(List<Plan> plans) {
    List<UUID> ids = plans.stream().flatMap(plan -> plan.ids.stream()).map(UUID::fromString).toList();
    for (UUID id : ids) {
      assertThat(jdbc.sql("SELECT count(*) FROM operations WHERE payment_id = ? AND status = 'IN_FLIGHT'").param(id).query(Long.class).single())
          .as("operations in flight of %s", id).isZero();
      assertThat(jdbc.sql("SELECT status FROM payments WHERE id = ?").param(id).query(String.class).single())
          .as("status of %s", id).isNotIn("PENDING", "AUTHORIZED");
      assertThat(jdbc.sql("SELECT count(*) FROM outbox_events WHERE payment_id = ? AND status <> 'DELIVERED'").param(id).query(Long.class).single())
          .as("events of %s not delivered", id).isZero();
    }
  }

  /** Each planted settlement error was reported as what it is, and nothing else was. */
  private int everyPlantedErrorIsReportedAndNothingElse(List<Plan> plans, Map<String, Set<String>> expected, List<String> fileIds) {
    int reported = 0;
    for (Plan plan : plans) {
      for (String id : plan.ids) {
        Set<String> actual = new TreeSet<>(jdbc.sql("SELECT type FROM discrepancies WHERE payment_id = ?").param(UUID.fromString(id)).query(String.class).list());
        assertThat(actual).as("discrepancies of %s %s", plan.order, plan.faults).isEqualTo(expected.getOrDefault(id, Set.of()));
        reported += actual.size();
      }
    }
    long unknown = jdbc.sql("""
        SELECT count(*) FROM discrepancies d JOIN settlement_lines l ON l.id = d.line_id
        WHERE l.file_id = ANY(?) AND d.type = 'UNKNOWN_PAYMENT'
        """).param(fileIds.stream().map(UUID::fromString).toArray(UUID[]::new)).query(Long.class).single();
    assertThat(unknown).as("lines for payments that do not exist").isEqualTo(1);
    long waiting = jdbc.sql("SELECT count(*) FROM settlement_lines WHERE file_id = ANY(?) AND status IN ('NEW', 'NEEDS_SYNC')")
        .param(fileIds.stream().map(UUID::fromString).toArray(UUID[]::new)).query(Long.class).single();
    assertThat(waiting).as("lines still waiting").isZero();
    return reported + 1;
  }

  // ---------------------------------------------------------------- time and workers

  /** Moves time in steps, running every worker after each step. */
  private void settleDown(Duration step, int steps) {
    for (int i = 0; i < steps; i++) {
      advance(step);
      rounds();
    }
  }

  /** The workers, as their timers would run them, until nothing is due now. */
  private void rounds() {
    awaitWebhooks();
    for (int i = 0; i < 20; i++) {
      int work = world.app.getBean(RunDuePayments.class).handle(1_000) + world.app.getBean(RunDueEvents.class).handle(1_000);
      awaitWebhooks();
      if (work == 0) {
        return;
      }
    }
  }

  /** Webhooks travel on real threads; give the ones on their way a moment to arrive. */
  private void awaitWebhooks() {
    try {
      Thread.sleep(150);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // ---------------------------------------------------------------- helpers

  private void fault(Plan plan, Call call, Fault fault) {
    world.simulator.plan(plan.order, call, fault);
    count(plan, call + " " + fault);
  }

  private void count(Plan plan, String fault) {
    plan.faults.add(fault);
    faultCounts.merge(fault, 1, Integer::sum);
  }

  private <T> T pick(List<T> from) {
    return from.get(random.nextInt(from.size()));
  }

  private static String newOrder(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  private void writeReport(List<Plan> plans, int compared, int reported, Map<String, Set<String>> expected, Duration took) throws IOException {
    Map<String, Long> states = new TreeMap<>();
    for (Plan plan : plans) {
      for (String id : plan.ids) {
        states.merge(payment(id).text("status"), 1L, Long::sum);
      }
    }
    long late = plans.stream().flatMap(plan -> plan.ids.stream()).filter(id -> payment(id).json().path("late").asBoolean()).count();
    report.add("# Proof run");
    report.add("");
    report.add("| | |");
    report.add("|---|---|");
    report.add("| Seed | " + SEED + " |");
    report.add("| Orders | " + plans.size() + " |");
    report.add("| Payments | " + compared + " |");
    report.add("| Rounds | " + System.getProperty("mizan.test.parallel", "1") + " at a time |");
    report.add("| Took | " + took.toSeconds() + " s |");
    report.add("");
    report.add("## Where the payments ended");
    report.add("");
    report.add("| State | Payments |");
    report.add("|---|---|");
    states.forEach((state, n) -> report.add("| " + state + " | " + n + " |"));
    report.add("| of which late captures, returned | " + late + " |");
    report.add("");
    report.add("## Faults planted");
    report.add("");
    report.add("| Fault | Times |");
    report.add("|---|---|");
    faultCounts.forEach((fault, n) -> report.add("| " + fault + " | " + n + " |"));
    report.add("");
    report.add("## Checks");
    report.add("");
    report.add("| Check | Result |");
    report.add("|---|---|");
    report.add("| Each payment's captured and refunded totals equal the simulator's book | " + compared + " of " + compared + " |");
    report.add("| No card charged twice; an order keeps one payment's money at most; every late capture returned | Passed |");
    report.add("| No operation in flight, no payment waiting, every event delivered | Passed |");
    report.add("| The ledger balances; receivable, sales, suspense and bank agree (I1 to I4) | Passed |");
    report.add("| Every planted settlement error reported as itself, and nothing else | " + reported + " discrepancies, all expected |");
    report.add("| A payout with a wrong total refused whole | Passed |");
    Path file = Path.of("target", "proof", "report.md");
    Files.createDirectories(file.getParent());
    Files.write(file, report);
    System.out.println(String.join(System.lineSeparator(), report));
  }

}
