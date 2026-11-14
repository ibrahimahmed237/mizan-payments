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
    @DefaultValue HttpProperties http,
    @DefaultValue SettlementProperties settlement,
    @DefaultValue FeesProperties fees) {

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

  /** @param missingAfter how long a gateway may take to pay out a capture or take back a refund */
  public record SettlementProperties(@DefaultValue("3d") Duration missingAfter) {}

  /**
   * The fee table, from the store's contract with each gateway. The defaults are examples.
   *
   * @param percentBps hundredths of a percent: 250 is 2.5%
   * @param fixed halalas added to each capture
   * @param vatBps VAT on the fee, in hundredths of a percent
   */
  public record FeeProperties(int percentBps, long fixed, int vatBps) {}

  /** The fee rule of each gateway. One that is not set gets the example. */
  public record FeesProperties(FeeProperties card, FeeProperties payLater) {

    public FeesProperties {
      card = card == null ? new FeeProperties(250, 100, 1500) : card;
      payLater = payLater == null ? new FeeProperties(400, 0, 1500) : payLater;
    }
  }

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
