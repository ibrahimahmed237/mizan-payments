package dev.mizan.app.support;

import dev.mizan.app.application.RunDueLines;
import dev.mizan.app.application.RunDuePayments;
import dev.mizan.app.support.Api.Answer;
import dev.mizan.core.payment.Gateway;
import dev.mizan.simulator.SimPayment;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The base of every integration test: the shared world, the store's API, and small helpers that read like a story. */
public abstract class IntegrationTest {

  private static final AtomicInteger ORDERS = new AtomicInteger();

  protected final World world = World.get();
  protected final Api api = new Api(world.baseUrl, world.paymentsKey);
  protected final JdbcClient jdbc = world.jdbc;

  /** After every test, whatever it did: the books still hold. */
  @AfterEach
  void theBooksHold() {
    Books.check(jdbc);
    Books.checkSettlements(jdbc);
  }

  // ---------------------------------------------------------------- making things

  /** A new order of the store. Every test uses its own. */
  protected static String newOrder() {
    return "order-" + ORDERS.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  /** A token for a test card: 1111 pays at once, 3333 needs the bank's code, 0002 and 0069 are declined. */
  protected String token(String last4) {
    return world.simulator.createToken("588845000000" + last4, "mada", false);
  }

  /** The body of a card payment for an order. */
  protected Map<String, Object> card(String order, long amount, String last4, String capture) {
    return Api.body(
        "amount", amount, "currency", "SAR", "reference", order, "method", "CARD",
        "source", Map.of("token", token(last4)), "capture", capture, "return_url", World.RETURN_PREFIX + "/" + order);
  }

  protected Map<String, Object> payLater(String order, long amount, String capture) {
    return Api.body(
        "amount", amount, "currency", "SAR", "reference", order, "method", "PAY_LATER",
        "capture", capture, "return_url", World.RETURN_PREFIX + "/" + order);
  }

  /** Creates a card payment and returns the API's answer. */
  protected Answer pay(String order, long amount, String last4, String capture) {
    return api.post("/v1/payments", card(order, amount, last4, capture));
  }

  /** A payment that is captured: the card pays at once. Returns its id. */
  protected String captured(String order, long amount) {
    Answer created = pay(order, amount, "1111", "AUTO");
    if (created.status() != 201 || !"CAPTURED".equals(created.text("status"))) {
      throw new AssertionError("Expected a captured payment, got " + created.raw().body());
    }
    return created.text("id");
  }

  /** A payment that is authorized: a hold on the card. Returns its id. */
  protected String authorized(String order, long amount) {
    Answer created = pay(order, amount, "1111", "MANUAL");
    if (created.status() != 201 || !"AUTHORIZED".equals(created.text("status"))) {
      throw new AssertionError("Expected an authorized payment, got " + created.raw().body());
    }
    return created.text("id");
  }

  // ---------------------------------------------------------------- looking

  protected Answer payment(String id) {
    return api.get("/v1/payments/" + id);
  }

  /** Asks Mizan to look at the gateway now, as the sync worker would. */
  protected Answer sync(String id) {
    return api.send("POST", "/v1/payments/" + id + "/sync", null, null);
  }

  /** One round of the sync worker, as its timer would run it. Returns how many payments it took. */
  protected int round() {
    return world.app.getBean(RunDuePayments.class).handle(1_000);
  }

  /** The simulator's own record of a payment: what really happened at the gateway. */
  protected SimPayment atGateway(String gatewayPaymentId) {
    return world.simulator.payment(gatewayPaymentId);
  }

  protected String gatewayId(String paymentId) {
    return column(paymentId, "gateway_payment_id");
  }

  /** The types of the events written for the store about a payment, oldest first. */
  protected List<String> events(String paymentId) {
    return jdbc.sql("SELECT type FROM outbox_events WHERE payment_id = ? ORDER BY seq")
        .param(UUID.fromString(paymentId)).query(String.class).list();
  }

  /** The kinds of the ledger entries of a payment with their amounts, such as {@code CAPTURE 15000}, oldest first. */
  protected List<String> entries(String paymentId) {
    return jdbc.sql("""
        SELECT e.kind || ' ' || (SELECT sum(amount) FROM ledger_postings l WHERE l.entry_id = e.id AND l.side = 'DEBIT')
        FROM ledger_entries e WHERE e.payment_id = ? ORDER BY e.seq
        """).param(UUID.fromString(paymentId)).query(String.class).list();
  }

  /** A payment's operations as {@code TYPE STATUS ORIGIN amount}, oldest first. */
  protected List<String> operations(String paymentId) {
    return jdbc.sql("SELECT type || ' ' || status || ' ' || origin || ' ' || amount FROM operations WHERE payment_id = ? ORDER BY seq")
        .param(UUID.fromString(paymentId)).query(String.class).list();
  }

  /** One column of a payment's row, as text. */
  protected String column(String paymentId, String column) {
    // A list, because the value may be null.
    return jdbc.sql("SELECT " + column + "::text FROM payments WHERE id = ?").param(UUID.fromString(paymentId)).query(String.class).list().get(0);
  }

  /** A time column of a payment's row; null when it is empty. */
  protected Instant time(String paymentId, String column) {
    return jdbc.sql("SELECT " + column + " FROM payments WHERE id = ?").param(UUID.fromString(paymentId))
        .query((row, n) -> {
          OffsetDateTime value = row.getObject(1, OffsetDateTime.class);
          return value == null ? Instant.EPOCH : value.toInstant();
        })
        .optional().filter(value -> !value.equals(Instant.EPOCH)).orElse(null);
  }

  /** The test clock's time now. It only moves when a test moves it. */
  protected Instant now() {
    return world.clock.instant();
  }

  /** The webhooks Mizan has written down for a gateway payment, as their types, oldest first. */
  protected List<String> webhooks(String gatewayPaymentId) {
    return jdbc.sql("SELECT type FROM gateway_events WHERE gateway_payment_id = ? ORDER BY id").param(gatewayPaymentId).query(String.class).list();
  }

  /** Waits a short real time for something that happens in the background: a webhook on its way, for example. */
  protected static void await(BooleanSupplier condition) {
    long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > end) {
        throw new AssertionError("The condition did not become true in 5 seconds.");
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }

  // ---------------------------------------------------------------- settlement

  private static final AtomicInteger PAYOUT_DAYS = new AtomicInteger();

  /** Finance's API. */
  protected final Api finance = new Api(world.baseUrl, world.backofficeKey);

  /**
   * The gateway closes a payout now: every capture and refund it has not paid out yet, of every test so far, goes
   * into one settlement file. Each call has a date of its own, so a payout is never answered from an earlier one.
   */
  protected String payout(Gateway gateway) {
    return world.simulator.closePayout(gateway, LocalDate.of(2030, 1, 1).plusDays(PAYOUT_DAYS.incrementAndGet()));
  }

  /**
   * Closes a payout of each gateway and throws the files away. A test calls this before it makes its own payments,
   * so that its next payout holds only its own money.
   */
  protected void freshPayouts() {
    payout(Gateway.CARD_GATEWAY);
    payout(Gateway.PAY_LATER);
  }

  protected Answer upload(String file) {
    return finance.send("POST", "/v1/settlements", file, null);
  }

  /** Rounds of the matching worker until no line waits that is due now. */
  protected void match() {
    while (world.app.getBean(RunDueLines.class).handle(1_000) > 0) {
      // more may be due: a line whose payment was just synced
    }
  }

  /** A file's lines as {@code status type gross}, in file order. */
  protected List<String> lines(String fileId) {
    return jdbc.sql("SELECT status || ' ' || type || ' ' || gross FROM settlement_lines WHERE file_id = ? ORDER BY line_number")
        .param(UUID.fromString(fileId)).query(String.class).list();
  }

  /** The discrepancies about a payment, as {@code TYPE STATUS expected actual suspense}, oldest first. */
  protected List<String> discrepancies(String paymentId) {
    return jdbc.sql("""
        SELECT type || ' ' || status || ' ' || coalesce(expected::text, '-') || ' ' || coalesce(actual::text, '-') || ' ' || suspense_amount
        FROM discrepancies WHERE payment_id = ? ORDER BY seq
        """).param(UUID.fromString(paymentId)).query(String.class).list();
  }

  /** The card gateway's fee for a capture, from the simulator's contract. */
  protected long cardFee(long gross) {
    return world.simulator.config().cardFee().feeFor(gross);
  }

  // ---------------------------------------------------------------- time

  /** Moves the clock of Mizan and of the simulator, and lets the simulator do what became due. */
  protected void advance(Duration by) {
    world.clock.advance(by);
    world.simulator.runDueTasks();
  }
}
