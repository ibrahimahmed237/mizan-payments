package dev.mizan.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.app.support.Api.Answer;
import dev.mizan.app.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.RepeatedTest;

/** Requests that arrive at the same moment. Each race runs several times. See docs/lld/payments.md, "Races". */
class RaceIT extends IntegrationTest {

  private static final int ROUNDS = 5;

  /** Runs the calls at the same moment and returns their answers in the order given. */
  private static List<Answer> together(List<Callable<Answer>> calls) throws Exception {
    ExecutorService threads = Executors.newFixedThreadPool(calls.size());
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Answer>> running = new ArrayList<>();
      for (Callable<Answer> call : calls) {
        running.add(threads.submit(() -> {
          start.await();
          return call.call();
        }));
      }
      start.countDown();
      List<Answer> answers = new ArrayList<>();
      for (Future<Answer> future : running) {
        answers.add(future.get());
      }
      return answers;
    } finally {
      threads.shutdownNow();
    }
  }

  private static long count(List<Answer> answers, int status) {
    return answers.stream().filter(answer -> answer.status() == status).count();
  }

  @RepeatedTest(ROUNDS)
  void two_requests_with_the_same_key_make_one_payment() throws Exception {
    String order = newOrder();
    String key = "key-" + UUID.randomUUID();
    Map<String, Object> body = card(order, 15_000, "1111", "AUTO");

    List<Answer> answers = together(List.of(() -> api.post("/v1/payments", body, key), () -> api.post("/v1/payments", body, key)));

    // One made it, the other waited for the first one's transaction and then got the same payment.
    assertThat(count(answers, 201)).isEqualTo(1);
    assertThat(count(answers, 200)).isEqualTo(1);
    assertThat(answers.get(0).text("id")).isEqualTo(answers.get(1).text("id"));
    assertThat(world.simulator.paymentsOf(order)).hasSize(1);
    assertThat(world.simulator.paymentsOf(order).get(0).charges()).isEqualTo(1);
  }

  @RepeatedTest(ROUNDS)
  void two_payments_for_one_order_at_once_charge_the_customer_once() throws Exception {
    String order = newOrder();
    Map<String, Object> one = card(order, 15_000, "1111", "AUTO");
    Map<String, Object> two = card(order, 15_000, "1111", "AUTO");

    List<Answer> answers = together(List.of(() -> api.post("/v1/payments", one), () -> api.post("/v1/payments", two)));

    // The second is refused: while the first one's result is not known, or because the order is paid by then.
    assertThat(count(answers, 201)).isEqualTo(1);
    assertThat(count(answers, 409)).isEqualTo(1);
    assertThat(answers.stream().filter(a -> a.status() == 409).findFirst().orElseThrow().code())
        .isIn("previous_payment_unresolved", "reference_already_paid");
    assertThat(world.simulator.paymentsOf(order).stream().mapToInt(p -> p.charges()).sum()).isEqualTo(1);
  }

  @RepeatedTest(ROUNDS)
  void a_capture_and_a_cancel_together_do_not_both_happen() throws Exception {
    String id = authorized(newOrder(), 15_000);

    List<Answer> answers = together(List.of(
        () -> api.post("/v1/payments/" + id + "/capture", null), () -> api.post("/v1/payments/" + id + "/cancel", null)));

    Answer capture = answers.get(0);
    Answer cancel = answers.get(1);
    boolean captured = capture.status() == 201 && "SUCCEEDED".equals(capture.text("status"));
    boolean canceled = cancel.status() == 201 && "SUCCEEDED".equals(cancel.text("status"));
    assertThat(captured ^ canceled).as("exactly one of the two happened").isTrue();
    assertThat(payment(id).text("status")).isEqualTo(captured ? "CAPTURED" : "CANCELED");
    assertThat(atGateway(id).captured()).isEqualTo(captured ? 15_000 : 0);
    assertThat(entries(id)).hasSize(captured ? 1 : 0);
  }

  @RepeatedTest(ROUNDS)
  void two_full_refunds_together_give_the_money_back_once() throws Exception {
    String id = captured(newOrder(), 15_000);

    List<Answer> answers = together(List.of(
        () -> api.post("/v1/payments/" + id + "/refunds", null), () -> api.post("/v1/payments/" + id + "/refunds", null)));

    // The other is refused: in progress, or nothing is left by then.
    assertThat(count(answers, 201)).isEqualTo(1);
    assertThat(answers.stream().filter(a -> a.status() != 201).findFirst().orElseThrow().code()).isIn("operation_in_progress", "refund_too_large");
    assertThat(atGateway(id).refunded()).isEqualTo(15_000);
    assertThat(payment(id).number("refunded_amount")).isEqualTo(15_000);
    assertThat(entries(id)).containsExactly("CAPTURE 15000", "REFUND 15000");
  }

  @RepeatedTest(ROUNDS)
  void two_part_refunds_together_never_go_above_what_was_captured() throws Exception {
    String id = captured(newOrder(), 15_000);

    List<Answer> answers = together(List.of(
        () -> api.post("/v1/payments/" + id + "/refunds", Map.of("amount", 10_000)),
        () -> api.post("/v1/payments/" + id + "/refunds", Map.of("amount", 10_000))));

    assertThat(count(answers, 201)).isEqualTo(1);
    assertThat(atGateway(id).refunded()).isEqualTo(10_000);
    assertThat(payment(id).number("refunded_amount")).isEqualTo(10_000);
  }

  @RepeatedTest(ROUNDS)
  void many_looks_at_once_write_the_capture_once() throws Exception {
    String id = pay(newOrder(), 15_000, "3333", "AUTO").text("id");
    world.simulator.customerApproves(id);
    List<Callable<Answer>> looks = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      looks.add(() -> sync(id));
    }

    List<Answer> answers = together(looks);

    assertThat(count(answers, 200)).isEqualTo(6);
    assertThat(entries(id)).containsExactly("CAPTURE 15000");
    assertThat(events(id)).containsExactly("payment.captured");
  }

  @RepeatedTest(ROUNDS)
  void two_tabs_approved_at_once_end_with_one_payment_kept_and_one_returned() throws Exception {
    String order = newOrder();
    String first = pay(order, 15_000, "3333", "AUTO").text("id");
    String second = pay(order, 15_000, "3333", "AUTO").text("id");
    world.simulator.customerApproves(first);
    world.simulator.customerApproves(second);

    together(List.of(() -> sync(first), () -> sync(second)));

    // Both took money at the bank. Whichever Mizan heard of first is kept; the other is late and is returned.
    List<String> late = new ArrayList<>(List.of(payment(first).json().path("late").asString(), payment(second).json().path("late").asString()));
    assertThat(late).containsExactlyInAnyOrder("true", "false");
    assertThat(List.of(payment(first).number("refunded_amount"), payment(second).number("refunded_amount")))
        .containsExactlyInAnyOrder(15_000L, 0L);
    assertThat(atGateway(first).refunded() + atGateway(second).refunded()).isEqualTo(15_000);
    assertThat(payment(first).text("status")).isEqualTo("CAPTURED");
    assertThat(payment(second).text("status")).isEqualTo("CAPTURED");
  }
}
