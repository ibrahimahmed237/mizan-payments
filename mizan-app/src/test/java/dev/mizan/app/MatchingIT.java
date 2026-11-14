package dev.mizan.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.app.application.MatchLine;
import dev.mizan.app.application.port.Settlements;
import dev.mizan.app.support.Books;
import dev.mizan.app.support.IntegrationTest;
import dev.mizan.core.payment.Gateway;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The matching worker: its lease, two workers at once, a crash in the middle of a file. */
class MatchingIT extends IntegrationTest {

  private final Settlements settlements = world.app.getBean(Settlements.class);

  @BeforeEach
  void onlyThisTestsMoney() {
    freshPayouts();
  }

  private List<UUID> lineIds(String fileId) {
    return jdbc.sql("SELECT id FROM settlement_lines WHERE file_id = ? ORDER BY line_number").param(UUID.fromString(fileId)).query(UUID.class).list();
  }

  @Test
  void a_worker_that_dies_in_the_middle_of_a_file_is_followed_by_another_that_finishes_it() {
    for (int i = 0; i < 3; i++) {
      captured(newOrder(), 10_000 + i);
    }
    String file = upload(payout(Gateway.CARD_GATEWAY)).text("id");
    List<UUID> ids = lineIds(file);

    // A worker took all three lines and matched one. Then it died.
    assertThat(settlements.claimDue(now(), now().plus(MatchLine.LEASE), 1_000)).containsAll(ids);
    world.app.getBean(MatchLine.class).handle(ids.get(0));
    match();
    assertThat(lines(file)).containsExactly("MATCHED CAPTURE 10000", "NEW CAPTURE 10001", "NEW CAPTURE 10002"); // leased, untouched

    advance(Duration.ofSeconds(30)); // the lease ends
    match();

    assertThat(lines(file)).containsExactly("MATCHED CAPTURE 10000", "MATCHED CAPTURE 10001", "MATCHED CAPTURE 10002");
  }

  @Test
  void a_line_that_is_matched_twice_is_settled_once() {
    String id = captured(newOrder(), 15_000);
    String file = upload(payout(Gateway.CARD_GATEWAY)).text("id");
    UUID line = lineIds(file).get(0);
    long bank = Books.balance(jdbc, "bank");

    world.app.getBean(MatchLine.class).handle(line);
    world.app.getBean(MatchLine.class).handle(line); // a second worker, after its lease ended

    assertThat(column(id, "settled_captured")).isEqualTo("15000");
    assertThat(Books.balance(jdbc, "bank") - bank).isEqualTo(15_000 - cardFee(15_000));
  }

  @Test
  void two_workers_at_once_settle_every_line_once() throws Exception {
    for (int i = 0; i < 8; i++) {
      captured(newOrder(), 10_000 + i);
    }
    String file = upload(payout(Gateway.CARD_GATEWAY)).text("id");
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      Future<?> one = workers.submit(() -> {
        start.await();
        match();
        return null;
      });
      Future<?> two = workers.submit(() -> {
        start.await();
        match();
        return null;
      });
      start.countDown();
      one.get(60, TimeUnit.SECONDS);
      two.get(60, TimeUnit.SECONDS);
    } finally {
      workers.shutdownNow();
    }

    assertThat(lines(file)).hasSize(8).allMatch(line -> line.startsWith("MATCHED "));
    // The books are checked after the test, as always: the bank holds each line's net once.
  }

  @Test
  void lines_are_taken_in_file_order() {
    for (int i = 0; i < 3; i++) {
      captured(newOrder(), 10_000 + i);
    }
    match(); // nothing of earlier tests is due any more
    String file = upload(payout(Gateway.CARD_GATEWAY)).text("id");
    List<UUID> ids = lineIds(file);

    assertThat(settlements.claimDue(now(), now().plus(MatchLine.LEASE), 2)).isEqualTo(ids.subList(0, 2));
    match();
    advance(Duration.ofSeconds(30));
    match();
  }
}
