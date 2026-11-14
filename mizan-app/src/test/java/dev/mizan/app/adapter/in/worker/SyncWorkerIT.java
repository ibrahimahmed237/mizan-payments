package dev.mizan.app.adapter.in.worker;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.app.support.IntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The worker's own timer. Every other test switches the workers off and runs rounds by hand; this one lets the
 * timer run, for a moment, to show that it is wired: it starts by itself and it runs rounds.
 */
class SyncWorkerIT extends IntegrationTest {

  @Test
  void with_the_workers_on_a_payment_is_advanced_without_anyone_asking() {
    String id = pay(newOrder(), 15_000, "3333", "AUTO").text("id");
    world.simulator.customerApproves(id);
    advance(Duration.ofSeconds(15)); // the payment's first look is due

    try (ConfigurableApplicationContext withWorkers = world.startWith("mizan.workers.enabled=true", "mizan.workers.sync-every=50ms")) {
      assertThat(withWorkers.getBeansOfType(SyncWorker.class)).hasSize(1);

      await(() -> "CAPTURED".equals(payment(id).text("status")));
    }

    assertThat(events(id)).containsExactly("payment.captured");
  }

  @Test
  void with_the_workers_on_a_settlement_file_is_matched_without_anyone_asking() {
    freshPayouts();
    String id = captured(newOrder(), 15_000);
    String file = upload(payout(dev.mizan.core.payment.Gateway.CARD_GATEWAY)).text("id");

    try (ConfigurableApplicationContext withWorkers = world.startWith("mizan.workers.enabled=true", "mizan.workers.match-every=50ms")) {
      assertThat(withWorkers.getBeansOfType(MatchingWorker.class)).hasSize(1);
      assertThat(withWorkers.getBeansOfType(MissingSettlementWorker.class)).hasSize(1);

      await(() -> lines(file).equals(java.util.List.of("MATCHED CAPTURE 15000")));
    }

    assertThat(column(id, "settled_captured")).isEqualTo("15000");
  }

  @Test
  void with_the_workers_off_there_is_no_timer() {
    assertThat(world.app.getBeansOfType(SyncWorker.class)).isEmpty();
    assertThat(world.app.getBeansOfType(MatchingWorker.class)).isEmpty();
    assertThat(world.app.getBeansOfType(MissingSettlementWorker.class)).isEmpty();
  }

  @Test
  void the_daily_check_and_the_rounds_survive_a_failure() {
    new MissingSettlementWorker(failing(dev.mizan.app.application.CheckMissingSettlements.class)).run();
    new SyncWorker(failing(dev.mizan.app.application.RunDuePayments.class)).run();
    new MatchingWorker(failing(dev.mizan.app.application.RunDueLines.class)).run();
    // No exception came out: the next run of the timer will come.
  }

  private static <T> T failing(Class<T> type) {
    T mock = org.mockito.Mockito.mock(type, invocation -> {
      throw new IllegalStateException("the database is down");
    });
    return mock;
  }
}
