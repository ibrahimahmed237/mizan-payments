package dev.mizan.app.application;

import dev.mizan.app.application.PaymentWriter.Saved;
import dev.mizan.app.application.model.IdempotencyRecord;
import dev.mizan.app.application.model.OperationRecord;
import dev.mizan.app.application.model.Origin;
import dev.mizan.app.application.model.PaymentRecord;
import dev.mizan.app.application.port.GatewayClient;
import dev.mizan.app.application.port.GatewayResult;
import dev.mizan.app.application.port.Gateways;
import dev.mizan.app.application.port.IdempotencyKeys;
import dev.mizan.app.application.port.Operations;
import dev.mizan.app.application.port.Payments;
import dev.mizan.app.application.port.Transactions;
import dev.mizan.app.application.view.PaymentView;
import dev.mizan.core.money.Money;
import dev.mizan.core.operation.CommandRules;
import dev.mizan.core.payment.CaptureMode;
import dev.mizan.core.payment.Method;
import dev.mizan.core.payment.OperationType;
import dev.mizan.core.payment.Payment;
import dev.mizan.core.payment.PaymentStatus;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Starts a payment. See docs/lld/payments.md, "Create". */
@Service
public class CreatePayment {

  /**
   * What the store sent. Nothing is trusted yet.
   *
   * @param token the card or wallet token. It is passed to the gateway and kept nowhere
   * @param requestHash a hash of the request, to tell a repeat from another request with the same key
   */
  public record Command(
      Long amount,
      String currency,
      String reference,
      String method,
      String token,
      String capture,
      String returnUrl,
      String description,
      String idempotencyKey,
      String requestHash) {

    /** Without the token: this text may end in a log. */
    @Override
    public String toString() {
      return "Command[reference=" + reference + ", amount=" + amount + ", method=" + method + "]";
    }
  }

  /** @param replay true when the key was used before and this is the payment it made then */
  public record Result(PaymentView payment, boolean replay) {}

  private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  private final Payments payments;
  private final Operations operations;
  private final IdempotencyKeys keys;
  private final Gateways gateways;
  private final OperationRunner runner;
  private final Transactions transactions;
  private final Settings settings;
  private final InstantSource clock;

  public CreatePayment(
      Payments payments,
      Operations operations,
      IdempotencyKeys keys,
      Gateways gateways,
      OperationRunner runner,
      Transactions transactions,
      Settings settings,
      InstantSource clock) {
    this.payments = payments;
    this.operations = operations;
    this.keys = keys;
    this.gateways = gateways;
    this.runner = runner;
    this.transactions = transactions;
    this.settings = settings;
    this.clock = clock;
  }

  /** What transaction 1 made: a new payment to send to the gateway, or the payment an earlier request made. */
  private record Started(PaymentRecord payment, OperationRecord operation, boolean replay) {}

  public Result handle(Command command) {
    Method method = parse(Method.class, command.method(), "invalid_method", "method");
    CaptureMode capture = command.capture() == null
        ? CaptureMode.AUTO
        : parse(CaptureMode.class, command.capture(), "invalid_capture", "capture");
    check(command, method);

    // Transaction 1: the payment exists, with its create in flight, before the gateway hears of it.
    Started started = transactions.run(() -> start(command, method, capture));
    if (started.replay()) {
      return new Result(PaymentView.of(started.payment(), started.operation()), true);
    }

    // The call, with no transaction open. Mizan's own id goes with it, so it can ask about this payment later.
    PaymentRecord payment = started.payment();
    GatewayClient gateway = gateways.of(payment.payment().gateway());
    GatewayResult answer = gateway.create(new GatewayClient.Create(
        payment.id(), payment.reference(), payment.payment().amount(), payment.payment().currency(), method, command.token(),
        capture == CaptureMode.MANUAL, settings.publicUrl() + "/v1/return/" + payment.id(), payment.description()));

    // Transaction 2: what came of it.
    Saved saved = runner.finish(payment.id(), started.operation().id(), answer);
    return new Result(PaymentView.of(saved.payment(), saved.inFlight()), false);
  }

  private Started start(Command command, Method method, CaptureMode capture) {
    Instant now = clock.instant();
    UUID id = UUID.randomUUID();
    var key = new IdempotencyRecord(command.idempotencyKey(), command.requestHash(), id, null);
    if (!keys.save(key, now, now.plus(settings.idempotencyKeysKept()))) {
      return replay(command);
    }

    payments.lockOrder(command.reference());
    List<PaymentRecord> others = payments.ofOrder(command.reference());
    boolean unknown = others.stream().anyMatch(this::resultUnknown);
    boolean money = others.stream().anyMatch(other -> CommandRules.hasMoney(other.payment()));
    CommandRules.newPaymentForOrder(unknown, money).ifPresent(refusal -> {
      throw Problem.of(refusal);
    });

    Payment fresh = Payment.pending(command.amount(), command.currency(), method.gateway(), capture, now.plus(settings.waitFor(method)));
    // At a gateway that lets the caller choose the payment's id, the id is Mizan's own, known before the call (rule R3).
    String gatewayId = method.gateway().callerChoosesId() ? id.toString() : null;
    var payment = new PaymentRecord(
        id, command.reference(), method, command.description(), command.returnUrl(), gatewayId, fresh, 0, 0, null, false, null, 0, 1, now, now);
    payments.insert(payment);
    OperationRecord create = runner.start(payment, UUID.randomUUID(), OperationType.CREATE, Origin.API, command.amount(), null, now);
    return new Started(payment, create, false);
  }

  /** The key was used before: by this same request, or by another one. */
  private Started replay(Command command) {
    IdempotencyRecord first = keys.find(command.idempotencyKey()).orElseThrow();
    // The hash covers the path, so the key of a capture, cancel or refund never has a payment's hash.
    if (!first.requestHash().equals(command.requestHash())) {
      throw Problem.invalid("idempotency_key_reused", "This Idempotency-Key was used for another request.");
    }
    PaymentRecord payment = payments.find(first.paymentId()).orElseThrow();
    return new Started(payment, operations.inFlight(payment.id()).orElse(null), true);
  }

  /** Pending, and the gateway's answer to its create is not known: nobody can say yet whether it will take money. */
  private boolean resultUnknown(PaymentRecord other) {
    return other.payment().status() == PaymentStatus.PENDING
        && operations.inFlight(other.id()).filter(operation -> operation.type() == OperationType.CREATE).isPresent();
  }

  private void check(Command command, Method method) {
    if (command.amount() == null || command.amount() < Settings.MIN_AMOUNT || command.amount() > settings.maxAmount()) {
      throw Problem.invalid(
          "invalid_amount", "The amount is in whole halalas, from " + Settings.MIN_AMOUNT + " to " + settings.maxAmount() + ".");
    }
    if (!Money.SAR.equals(command.currency())) {
      throw Problem.invalid("unsupported_currency", "The currency must be SAR.");
    }
    if (command.reference() == null || !REFERENCE.matcher(command.reference()).matches()) {
      throw Problem.invalid("invalid_reference", "The reference is 1 to 64 letters, digits, '-', '_' or '.'.");
    }
    boolean hasToken = command.token() != null && !command.token().isBlank();
    if (method == Method.PAY_LATER && command.token() != null) {
      throw Problem.invalid("source_not_allowed", "A pay-later payment has no source.");
    }
    if (method != Method.PAY_LATER && (!hasToken || command.token().length() > 255)) {
      throw Problem.invalid("source_required", "A card or wallet payment needs source.token.");
    }
    if (command.returnUrl() == null && method != Method.APPLE_PAY) {
      throw Problem.invalid("return_url_required", "This payment method needs return_url.");
    }
    if (command.returnUrl() != null && !ReturnUrls.allowed(command.returnUrl(), settings.returnUrlPrefixes())) {
      throw Problem.invalid("return_url_not_allowed", "The return_url must start with an address configured for this store.");
    }
    if (command.description() != null && command.description().length() > 255) {
      throw Problem.invalid("description_too_long", "The description is at most 255 characters.");
    }
  }

  private static <E extends Enum<E>> E parse(Class<E> type, String value, String code, String field) {
    Optional<E> found = value == null
        ? Optional.empty()
        : List.of(type.getEnumConstants()).stream().filter(constant -> constant.name().equals(value)).findFirst();
    return found.orElseThrow(() -> Problem.invalid(
        code, "The " + field + " must be one of " + List.of(type.getEnumConstants()) + "."));
  }
}
