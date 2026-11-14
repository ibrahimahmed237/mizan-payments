package dev.mizan.app.adapter.out.gateway;

import dev.mizan.app.application.port.GatewayClient;
import dev.mizan.app.application.port.GatewayResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Counts and times every gateway call by its result: {@code mizan.gateway.calls} and {@code mizan.gateway.call.seconds},
 * tagged with the gateway, the call, and {@code done}, {@code refused} or {@code unknown}. A rise of {@code unknown}
 * is the first sign of trouble at a gateway.
 */
final class MeteredGatewayClient implements GatewayClient {

  private final GatewayClient inner;
  private final String gateway;
  private final MeterRegistry meters;

  MeteredGatewayClient(GatewayClient inner, String gateway, MeterRegistry meters) {
    this.inner = inner;
    this.gateway = gateway;
    this.meters = meters;
  }

  @Override
  public GatewayResult create(Create request) {
    return measure("create", () -> inner.create(request));
  }

  @Override
  public GatewayResult fetch(String gatewayPaymentId) {
    return measure("fetch", () -> inner.fetch(gatewayPaymentId));
  }

  @Override
  public GatewayResult capture(String gatewayPaymentId, long amount) {
    return measure("capture", () -> inner.capture(gatewayPaymentId, amount));
  }

  @Override
  public GatewayResult cancel(String gatewayPaymentId) {
    return measure("cancel", () -> inner.cancel(gatewayPaymentId));
  }

  @Override
  public GatewayResult refund(String gatewayPaymentId, long amount, String refundKey) {
    return measure("refund", () -> inner.refund(gatewayPaymentId, amount, refundKey));
  }

  private GatewayResult measure(String call, Supplier<GatewayResult> work) {
    Timer.Sample sample = Timer.start(meters);
    GatewayResult result = work.get();
    String outcome = result.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    sample.stop(meters.timer("mizan.gateway.call.seconds", "gateway", gateway, "call", call, "result", outcome));
    meters.counter("mizan.gateway.calls", "gateway", gateway, "call", call, "result", outcome).increment();
    return result;
  }
}
