package dev.mizan.core.sync;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.core.payment.CaptureMode;
import dev.mizan.core.payment.Gateway;
import dev.mizan.core.payment.GatewayStatus;
import dev.mizan.core.payment.Operation;
import dev.mizan.core.payment.OperationType;
import dev.mizan.core.payment.Payment;
import dev.mizan.core.payment.PaymentStatus;
import dev.mizan.core.sync.Effect.Captured;
import dev.mizan.core.sync.Effect.OperationFinished;
import dev.mizan.core.sync.Effect.Refunded;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** Properties P1 to P7 of docs/lld/sync.md, for generated payments, records and orders. */
class SyncPropertiesTest {

  private static final Instant NOW = Instant.parse("2026-11-14T09:00:00Z");
  private static final long AMOUNT = 1_000;

  /** A payment in any state Mizan can hold, with the gateway's last status that fits it. */
  @Provide
  Arbitrary<Payment> payments() {
    Arbitrary<Gateway> gateway = Arbitraries.of(Gateway.class);
    Arbitrary<CaptureMode> mode = Arbitraries.of(CaptureMode.class);
    Arbitrary<Payment> base = Combinators.combine(gateway, mode)
        .as((g, m) -> Payment.pending(AMOUNT, "SAR", g, m, NOW.plus(Duration.ofMinutes(30))));
    Arbitrary<Integer> shape = Arbitraries.integers().between(0, 7);
    Arbitrary<Long> captured = Arbitraries.longs().between(1, AMOUNT);
    Arbitrary<Long> part = Arbitraries.longs().between(0, AMOUNT);
    return Combinators.combine(base, shape, captured, part).as((payment, kind, c, r) -> switch (kind) {
      case 0 -> payment;
      case 1 -> payment.with(p -> p.gatewayStatus = GatewayStatus.INITIATED);
      case 2 -> payment.with(p -> {
        p.status = PaymentStatus.AUTHORIZED;
        p.gatewayStatus = GatewayStatus.AUTHORIZED;
        p.holdEndsAt = NOW.plus(Duration.ofDays(7));
      });
      case 3 -> payment.with(p -> {
        p.status = PaymentStatus.CAPTURED;
        p.gatewayStatus = GatewayStatus.PAID;
        p.captured = c;
        p.refunded = Math.min(r, c);
      });
      case 4 -> payment.with(p -> {
        p.status = PaymentStatus.FAILED;
        p.gatewayStatus = GatewayStatus.FAILED;
      });
      case 5 -> payment.with(p -> {
        p.status = PaymentStatus.CANCELED;
        p.gatewayStatus = GatewayStatus.VOIDED;
      });
      case 6 -> payment.with(p -> {
        p.status = PaymentStatus.EXPIRED;
        p.gatewayStatus = GatewayStatus.INITIATED; // expired by Mizan's own timer
      });
      default -> payment.with(p -> p.status = PaymentStatus.CANCELED); // canceled here before any record was seen
    });
  }

  /** Any record a gateway could show for a payment of this amount. */
  @Provide
  Arbitrary<Snapshot> snapshots() {
    Arbitrary<Snapshot> noMoney = Arbitraries.of(
            GatewayStatus.INITIATED, GatewayStatus.AUTHORIZED, GatewayStatus.FAILED, GatewayStatus.VOIDED, GatewayStatus.EXPIRED)
        .map(status -> Snapshot.of(status, AMOUNT, "SAR"));
    Arbitrary<Snapshot> money = Combinators.combine(Arbitraries.longs().between(1, AMOUNT), Arbitraries.longs().between(0, AMOUNT))
        .as((c, r) -> Snapshot.paid(AMOUNT, "SAR", c, Math.min(r, c)));
    return Arbitraries.oneOf(noMoney, money, Arbitraries.just(Snapshot.notFound()));
  }

  @Provide
  Arbitrary<Operation> operations() {
    return Combinators.combine(Arbitraries.of(OperationType.class), Arbitraries.longs().between(1, AMOUNT))
        .as((type, amount) -> new Operation(type, amount, 1, NOW.minusSeconds(5)))
        .injectNull(0.4);
  }

  @Provide
  Arbitrary<SyncContext> contexts() {
    return Combinators.combine(Arbitraries.of(LateCapturePolicy.class), Arbitraries.of(true, false))
        .as((policy, otherMoney) -> new SyncContext(policy, Duration.ofDays(7), otherMoney));
  }

  @Property(tries = 5000)
  void p1_the_same_record_twice_changes_nothing_the_second_time(
      @ForAll("payments") Payment payment,
      @ForAll("operations") Operation inFlight,
      @ForAll("snapshots") Snapshot snapshot,
      @ForAll("contexts") SyncContext context) {
    Decision first = Sync.decide(payment, inFlight, snapshot, context, NOW);
    Operation stillInFlight = first.has(OperationFinished.class) ? null : inFlight;

    Decision second = Sync.decide(first.payment(), stillInFlight, snapshot, context, NOW.plusSeconds(1));

    assertThat(second.effects()).isEmpty();
    assertThat(second.payment()).isEqualTo(first.payment());
  }

  @Property(tries = 5000)
  void p2_p3_p5_p7_money_only_moves_forward_and_the_effects_say_exactly_how_much(
      @ForAll("payments") Payment payment,
      @ForAll("operations") Operation inFlight,
      @ForAll("snapshots") Snapshot snapshot,
      @ForAll("contexts") SyncContext context) {
    // The payment's own constructor checks P5 and P7: a decision that broke them would throw here.
    Decision decision = Sync.decide(payment, inFlight, snapshot, context, NOW);
    Payment after = decision.payment();

    assertThat(after.captured()).isGreaterThanOrEqualTo(payment.captured());
    assertThat(after.refunded()).isGreaterThanOrEqualTo(payment.refunded());
    assertThat(decision.effects(Captured.class).stream().mapToLong(Captured::amount).sum())
        .isEqualTo(after.captured() - payment.captured());
    assertThat(decision.effects(Refunded.class).stream().mapToLong(Refunded::amount).sum())
        .isEqualTo(after.refunded() - payment.refunded());
    assertThat(decision.effects(OperationFinished.class)).hasSizeLessThanOrEqualTo(inFlight == null ? 0 : 1);
  }

  /** A life a payment can really have at the gateway: its records one after another. */
  @Provide
  Arbitrary<List<Snapshot>> histories() {
    Arbitrary<Boolean> held = Arbitraries.of(true, false);
    Arbitrary<Integer> ending = Arbitraries.integers().between(0, 4);
    Arbitrary<List<Long>> refunds = Arbitraries.longs().between(1, 300).list().ofMaxSize(3);
    Arbitrary<Long> captured = Arbitraries.longs().between(1, AMOUNT);
    return Combinators.combine(held, ending, captured, refunds).as((wasHeld, end, c, steps) -> {
      List<Snapshot> life = new ArrayList<>();
      life.add(Snapshot.of(GatewayStatus.INITIATED, AMOUNT, "SAR"));
      if (wasHeld) {
        life.add(Snapshot.of(GatewayStatus.AUTHORIZED, AMOUNT, "SAR"));
      }
      switch (end) {
        case 0 -> life.add(Snapshot.of(GatewayStatus.FAILED, AMOUNT, "SAR"));
        case 1 -> life.add(Snapshot.of(GatewayStatus.VOIDED, AMOUNT, "SAR"));
        case 2 -> life.add(Snapshot.of(GatewayStatus.EXPIRED, AMOUNT, "SAR"));
        case 3 -> {
          // still waiting or still held: no more records
        }
        default -> {
          life.add(Snapshot.paid(AMOUNT, "SAR", c, 0));
          long refunded = 0;
          for (long step : steps) {
            refunded = Math.min(c, refunded + step);
            life.add(Snapshot.paid(AMOUNT, "SAR", c, refunded));
          }
        }
      }
      return life;
    });
  }

  @Property(tries = 3000)
  void p4_the_order_of_the_records_does_not_matter(
      @ForAll("histories") List<Snapshot> life,
      @ForAll long seed,
      @ForAll Gateway gateway,
      @ForAll CaptureMode mode,
      @ForAll("contexts") SyncContext context) {
    Payment start = Payment.pending(AMOUNT, "SAR", gateway, mode, NOW.plus(Duration.ofMinutes(30)));
    List<Snapshot> shuffled = new ArrayList<>(life);
    shuffled.addAll(life.subList(0, life.size() / 2)); // some records come twice
    java.util.Collections.shuffle(shuffled, new java.util.Random(seed));

    Payment inOrder = apply(start, life, context);
    Payment anyOrder = apply(start, shuffled, context);

    assertThat(anyOrder.status()).isEqualTo(inOrder.status());
    assertThat(anyOrder.captured()).isEqualTo(inOrder.captured());
    assertThat(anyOrder.refunded()).isEqualTo(inOrder.refunded());
    assertThat(anyOrder.gatewayStatus()).isEqualTo(inOrder.gatewayStatus());
    assertThat(anyOrder.late()).isFalse();
  }

  @Property(tries = 2000)
  void p6_a_record_with_another_amount_changes_nothing(
      @ForAll("payments") Payment payment,
      @ForAll("operations") Operation inFlight,
      @ForAll("snapshots") Snapshot snapshot,
      @ForAll("contexts") SyncContext context) {
    if (snapshot.status() == GatewayStatus.NOT_FOUND) {
      return;
    }
    Snapshot otherAmount = new Snapshot(
        snapshot.status(), AMOUNT + 1, "SAR", snapshot.captured(), snapshot.refunded(), null, null, null, null, null);

    Decision decision = Sync.decide(payment, inFlight, otherAmount, context, NOW);

    assertThat(decision.payment()).isEqualTo(payment);
    assertThat(decision.effects()).containsExactly(new Effect.Anomaly(AnomalyType.AMOUNT_CONFLICT));
  }

  private static Payment apply(Payment start, List<Snapshot> records, SyncContext context) {
    Payment payment = start;
    for (Snapshot record : records) {
      payment = Sync.decide(payment, null, record, context, NOW).payment();
    }
    return payment;
  }
}
