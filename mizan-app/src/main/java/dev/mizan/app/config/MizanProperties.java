package dev.mizan.app.config;

import dev.mizan.core.sync.LateCapturePolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Mizan's settings, read from {@code application.yml} and the environment. See docs/lld/running.md.
 *
 * @param publicUrl Mizan's own address, for the customer's return
 * @param returnUrlPrefixes allowed starts of a payment's return address
 */
@ConfigurationProperties("mizan")
public record MizanProperties(
    String publicUrl,
    @DefaultValue List<String> returnUrlPrefixes,
    @DefaultValue PaymentProperties payment,
    @DefaultValue OperationProperties operation,
    @DefaultValue GatewayProperties cardGateway,
    @DefaultValue GatewayProperties payLater,
    @DefaultValue HttpProperties http) {

  /**
   * @param maxAmount the largest payment, in halalas
   * @param cardWait how long a customer may take on the bank's page
   * @param payLaterWait how long a customer may take on the provider's page
   * @param holdWindow how long an authorized payment can be captured
   * @param lateCapture what to do with money taken on a payment that had ended
   */
  public record PaymentProperties(
      @DefaultValue("10000000") long maxAmount,
      @DefaultValue("30m") Duration cardWait,
      @DefaultValue("2h") Duration payLaterWait,
      @DefaultValue("7d") Duration holdWindow,
      @DefaultValue("REFUND") LateCapturePolicy lateCapture) {}

  /**
   * @param quietTime how long after an operation's last call its result may still arrive
   * @param idempotencyKeysKept how long an idempotency key is remembered
   */
  public record OperationProperties(@DefaultValue("1m") Duration quietTime, @DefaultValue("30d") Duration idempotencyKeysKept) {}

  /** One gateway: its address, Mizan's key for it, and the secret that checks its webhooks. */
  public record GatewayProperties(String url, String key, String webhookSecret) {}

  /** Time limits of a gateway call. */
  public record HttpProperties(@DefaultValue("2s") Duration connectTimeout, @DefaultValue("10s") Duration timeout) {}

  /** Stops the start when a needed setting is missing, and says which. */
  public MizanProperties {
    List<String> missing = new ArrayList<>();
    require(missing, "MIZAN_PUBLIC_URL", publicUrl);
    require(missing, "MIZAN_CARD_GATEWAY_URL", cardGateway.url());
    require(missing, "MIZAN_CARD_GATEWAY_KEY", cardGateway.key());
    require(missing, "MIZAN_CARD_GATEWAY_WEBHOOK_SECRET", cardGateway.webhookSecret());
    require(missing, "MIZAN_PAY_LATER_URL", payLater.url());
    require(missing, "MIZAN_PAY_LATER_KEY", payLater.key());
    require(missing, "MIZAN_PAY_LATER_WEBHOOK_SECRET", payLater.webhookSecret());
    if (returnUrlPrefixes.isEmpty()) {
      missing.add("MIZAN_RETURN_URL_PREFIXES");
    }
    if (!missing.isEmpty()) {
      throw new IllegalStateException("Mizan cannot start. These settings are missing: " + String.join(", ", missing));
    }
    if (operation.quietTime().compareTo(http.timeout()) <= 0) {
      throw new IllegalStateException(
          "mizan.operation.quiet-time must be longer than mizan.http.timeout: a call must have ended before its result is decided.");
    }
  }

  private static void require(List<String> missing, String name, String value) {
    if (value == null || value.isBlank()) {
      missing.add(name);
    }
  }
}
