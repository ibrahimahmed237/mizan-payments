package dev.mizan.app.adapter.in.web;

import dev.mizan.app.application.Problem;
import dev.mizan.app.application.ReceiveWebhook;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
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

  private final WebhookReader reader;
  private final ReceiveWebhook receive;

  WebhooksController(WebhookReader reader, ReceiveWebhook receive) {
    this.reader = reader;
    this.receive = receive;
  }

  @PostMapping("/v1/webhooks/{gateway}")
  Map<String, Boolean> webhook(
      @PathVariable String gateway,
      @RequestHeader(value = "X-Signature", required = false) String signature,
      HttpServletRequest request) throws IOException {
    byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
    if (body.length > MAX_BODY) {
      throw new Problem(Problem.Kind.TOO_LARGE, "body_too_large", "A webhook is at most 64 KB.");
    }
    receive.handle(switch (gateway) {
      case "card" -> reader.card(body);
      case "pay-later" -> reader.payLater(body, signature);
      default -> throw Problem.notFound("gateway");
    });
    return Map.of("received", true);
  }
}
