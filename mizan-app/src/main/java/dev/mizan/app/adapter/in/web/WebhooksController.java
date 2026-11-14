package dev.mizan.app.adapter.in.web;

import dev.mizan.app.application.Problem;
import dev.mizan.app.application.ReceiveWebhook;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where gateways send their webhooks. No API key: each gateway proves itself with its own secret. The answer is
 * 200 also for a repeat and for a payment Mizan does not have, so the gateway does not send it again for nothing.
 */
@RestController
class WebhooksController {

  /** A webhook is small. A larger body is not read. */
  static final int MAX_BODY = 64 * 1024;
  /** The gateways that send webhooks, as they are named in the address. */
  static final Set<String> GATEWAYS = Set.of("card", "pay-later");

  private final WebhookReader reader;
  private final ReceiveWebhook receive;
  private final MeterRegistry meters;

  WebhooksController(WebhookReader reader, ReceiveWebhook receive, MeterRegistry meters) {
    this.reader = reader;
    this.receive = receive;
    this.meters = meters;
  }

  @PostMapping("/v1/webhooks/{gateway}")
  Map<String, Boolean> webhook(
      @PathVariable String gateway,
      @RequestHeader(value = "X-Signature", required = false) String signature,
      HttpServletRequest request) throws IOException {
    // Before anything is counted: the name comes from the address, and a metric per made-up name would never end.
    if (!GATEWAYS.contains(gateway)) {
      throw Problem.notFound("gateway");
    }
    byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
    if (body.length > MAX_BODY) {
      throw new Problem(Problem.Kind.TOO_LARGE, "body_too_large", "A webhook is at most 64 KB.");
    }
    ReceiveWebhook.Event event;
    try {
      event = gateway.equals("card") ? reader.card(body) : reader.payLater(body, signature);
    } catch (Problem refused) {
      count(gateway, refused.code()); // many "unauthorized": a wrong secret, or someone trying
      throw refused;
    }
    count(gateway, receive.handle(event).name().toLowerCase(Locale.ROOT));
    return Map.of("received", true);
  }

  private void count(String gateway, String result) {
    meters.counter("mizan.webhooks", "gateway", gateway, "result", result).increment();
  }
}
