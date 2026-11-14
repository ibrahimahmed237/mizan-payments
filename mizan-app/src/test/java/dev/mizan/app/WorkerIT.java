package dev.mizan.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.app.application.AdvancePayment;
import dev.mizan.app.application.RunDuePayments;
import dev.mizan.app.application.port.Payments;
import dev.mizan.app.application.port.Transactions;
import dev.mizan.app.support.IntegrationTest;
import dev.mizan.simulator.Fault;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/** The sync worker's rounds: which payments it takes, the lease, and the schedule. See docs/lld/workers.md. */
class WorkerIT extends IntegrationTest {

  private final Payments payments = world.app.getBean(Payments.class);
  private final Transactions transactions = world.app.getBean(Transactions.class);

  @Test
  void a_round_takes_a_payment_only_when_its_time_has_come() {
    String id = pay(newOrder(), 15_000, "3333", "AUTO").text("id");
    world.simulator.customerApproves(id); // no webhook reaches Mizan: only the worker's own timer will find this

    advance(Duration.ofSeconds(14));
    round();
    assertThat(payment(id).text("status")).isEqualTo("PENDING"); // the first look is 15 seconds after the create

    advance(Duration.ofSeconds(1));
    round();
    assertThat(payment(id).text("status")).isEqualTo("CAPTURED");
    assertThat(events(id)).containsExactly("payment.captured");
  }

  @Test
  void a_waiting_payment_is_looked_at_less_and_less_often_until_its_time_ends() {
    String id = pay(newOrder(), 15_000, "3333", "AUTO").text("id");
    var created = now();

    // 15 s, 30 s, 1 min, 2 min, then every 5 min.
    for (int seconds : List.of(15, 45, 105, 225, 525, 825)) {
      assertThat(time(id, "next_sync_at")).isEqualTo(created.plusSeconds(seconds));
      advance(Duration.between(now(), created.plusSeconds(seconds)));
      round();
    }
    assertThat(payment(id).text("status")).isEqualTo("PENDING");
    // The last look is exactly when the waiting time ends.
    advance(Duration.between(now(), created.plus(Duration.ofMinutes(29))));
    round();
    assertThat(time(id, "next_sync_at")).isEqualTo(created.plus(Duration.ofMinutes(30)));
  }

  @Test
  void a_payment_that_has_ended_is_not_looked_at_again_by_itself() {
    String captured = captured(newOrder(), 15_000);
    String failed = pay(newOrder(), 15_000, "0002", "AUTO").text("id");

    assertThat(column(captured, "next_sync_at")).isNull();
    assertThat(column(failed, "next_sync_at")).isNull();
    assertThat(payments.claimDue(now().plus(Duration.ofDays(365)), now().plus(Duration.ofDays(366)), 10_000))
        .doesNotContain(UUID.fromString(captured), UUID.fromString(failed));
  }

  @Test
  void a_payment_that_a_worker_took_is_left_alone_until_the_lease_ends() {
    String id = pay(newOrder(), 15_000, "3333", "AUTO").text("id");
    UUID uuid = UUID.fromString(id);
    advance(Duration.ofSeconds(15));

    List<UUID> first = payments.claimDue(now(), now().plus(AdvancePayment.LEASE), 10_000);
    List<UUID> second = payments.claimDue(now(), now().plus(AdvancePayment.LEASE), 10_000);

    assertThat(first).contains(uuid);
    assertThat(second).doesNotContain(uuid); // another worker, in the same moment, does not take it
    assertThat(time(id, "next_sync_at")).isEqualTo(now().plusSeconds(30));

    // The worker that took it died before it did anything. When the lease ends, the payment is taken again.
    advance(Duration.ofSeconds(29));
    assertThat(payments.claimDue(now(), now().plus(AdvancePayment.LEASE), 10_000)).doesNotContain(uuid);
    advance(Duration.ofSeconds(1));
    assertThat(payments.claimDue(now(), now().plus(AdvancePayment.LEASE), 10_000)).contains(uuid);
  }

  @Test
  void a_round_takes_the_longest_waiting_first_and_no_more_than_its_limit() {
    String older = pay(newOrder(), 15_000, "3333", "AUTO").text("id");
    String newer = pay(newOrder(), 15_000, "3333", "AUTO").text("id");
    advance(Duration.ofSeconds(20));
    round(); // nothing is due any more, of these two or of other tests
    payments.requestSync(UUID.fromString(older), now());
    advance(Duration.ofSeconds(1));
    payments.requestSync(UUID.fromString(newer), now());

    List<UUID> one = payments.claimDue(now(), now().plus(AdvancePayment.LEASE), 1);

    assertThat(one).containsExactly(UUID.fromString(older));
    assertThat(payments.claimDue(now(), now().plus(AdvancePayment.LEASE), 10_000)).contains(UUID.fromString(newer));
  }

  @Test
  void a_payment_that_a_request_holds_right_now_is_skipped_not_waited_for() throws Exception {
    String id = pay(newOrder(), 15_000, "3333", "AUTO").text("id");
    UUID uuid = UUID.fromString(id);
    advance(Duration.ofSeconds(15));
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService other = Executors.newSingleThreadExecutor();
    try {
      Future<?> request = other.submit(() -> transactions.run(() -> {
        payments.lock(uuid); // a request works on this payment
        held.countDown();
        awaitQuietly(release);
      }));
      held.await();

      List<UUID> taken = payments.claimDue(now(), now().plus(AdvancePayment.LEASE), 10_000); // returns at once

      assertThat(taken).doesNotContain(uuid);
      release.countDown();
      request.get(5, TimeUnit.SECONDS);
      assertThat(payments.claimDue(now(), now().plus(AdvancePayment.LEASE), 10_000)).contains(uuid); // next round
    } finally {
      release.countDown();
      other.shutdownNow();
    }
  }

  @Test
  void two_workers_at_once_write_everything_once() throws Exception {
    String order = newOrder();
    String id = pay(order, 15_000, "3333", "AUTO").text("id");
    world.simulator.customerApproves(id);
    advance(Duration.ofSeconds(15));
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      Future<Integer> one = workers.submit(() -> {
        start.await();
        return round();
      });
      Future<Integer> two = workers.submit(() -> {
        start.await();
        return round();
      });
      start.countDown();
      one.get(30, TimeUnit.SECONDS);
      two.get(30, TimeUnit.SECONDS);
    } finally {
      workers.shutdownNow();
    }

    assertThat(payment(id).text("status")).isEqualTo("CAPTURED");
    assertThat(entries(id)).containsExactly("CAPTURE 15000");
    assertThat(events(id)).containsExactly("payment.captured");
  }

  @Test
  void a_round_looks_at_its_payments_side_by_side_so_a_slow_gateway_holds_up_nobody() {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      String id = pay(newOrder(), 15_000, "3333", "AUTO").text("id");
      world.simulator.customerApproves(id);
      ids.add(id);
    }
    advance(Duration.ofSeconds(15));
    world.simulatorServer.latency(Duration.ofMillis(200)); // a gateway that works, slowly
    world.simulatorServer.busiestSinceLastAsked();

    try (ConfigurableApplicationContext eightAtATime = world.startWith("mizan.workers.sync-parallel=8")) {
      eightAtATime.getBean(RunDuePayments.class).handle(50);
    } finally {
      world.simulatorServer.latency(Duration.ZERO);
    }

    // Each look waits 200 ms for the gateway; no transaction is open meanwhile (rule R5), so the looks overlap.
    assertThat(world.simulatorServer.busiestSinceLastAsked()).isGreaterThan(1);
    for (String id : ids) {
      assertThat(payment(id).text("status")).as(id).isEqualTo("CAPTURED");
      assertThat(entries(id)).containsExactly("CAPTURE 15000");
      assertThat(events(id)).containsExactly("payment.captured");
    }
  }

  @Test
  void a_dropped_webhook_is_covered_by_the_workers_own_timer() {
    String order = newOrder();
    world.webhooksFor(order);
    world.simulator.stick(order, Fault.WEBHOOK_DROP);
    String id = pay(order, 15_000, "3333", "AUTO").text("id");
    world.simulator.customerApproves(id);

    advance(Duration.ofSeconds(15));
    round();

    assertThat(webhooks(id)).isEmpty(); // nothing came in
    assertThat(payment(id).text("status")).isEqualTo("CAPTURED"); // and nothing was needed (rule R2)
  }

  @Test
  void a_gateway_that_cannot_be_asked_is_asked_again_later_and_later() {
    String order = newOrder();
    String id = pay(order, 15_000, "3333", "AUTO").text("id");
    world.simulator.customerApproves(id);
    world.simulator.fetchDown(order, 3);
    advance(Duration.ofSeconds(15));

    for (int wait : List.of(30, 60, 120)) { // 30 s, then doubling, up to ten minutes
      round();
      assertThat(payment(id).text("status")).isEqualTo("PENDING");
      assertThat(time(id, "next_sync_at")).isEqualTo(now().plusSeconds(wait));
      advance(Duration.ofSeconds(wait));
    }
    round(); // the gateway is back

    assertThat(payment(id).text("status")).isEqualTo("CAPTURED");
    assertThat(column(id, "sync_failures")).isEqualTo("0");
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
