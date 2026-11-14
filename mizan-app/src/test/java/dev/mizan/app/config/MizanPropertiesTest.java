package dev.mizan.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mizan.app.config.MizanProperties.FeeProperties;
import dev.mizan.app.config.MizanProperties.FeesProperties;
import dev.mizan.app.config.MizanProperties.GatewayProperties;
import dev.mizan.app.config.MizanProperties.HttpProperties;
import dev.mizan.app.config.MizanProperties.LimitsProperties;
import dev.mizan.app.config.MizanProperties.OperationProperties;
import dev.mizan.app.config.MizanProperties.PaymentProperties;
import dev.mizan.app.config.MizanProperties.SettlementProperties;
import dev.mizan.app.config.MizanProperties.StoreProperties;
import dev.mizan.core.sync.LateCapturePolicy;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The service refuses to start when a needed setting is missing, and says which. See docs/lld/running.md. */
class MizanPropertiesTest {

  private static final PaymentProperties PAYMENT =
      new PaymentProperties(10_000_000, Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofDays(7), LateCapturePolicy.REFUND);
  private static final GatewayProperties GATEWAY = new GatewayProperties("https://gateway.example", "key", "secret");
  private static final OperationProperties OPERATION = new OperationProperties(Duration.ofMinutes(1), Duration.ofDays(30));
  private static final HttpProperties HTTP = new HttpProperties(Duration.ofSeconds(2), Duration.ofSeconds(10));
  private static final SettlementProperties SETTLEMENT = new SettlementProperties(Duration.ofDays(3));
  private static final LimitsProperties LIMITS = new LimitsProperties(100, 200, 200, 400, 20, 40);
  private static final StoreProperties STORE = new StoreProperties(null, null);
  private static final FeesProperties FEES = new FeesProperties(new FeeProperties(250, 100, 1500), new FeeProperties(400, 0, 1500));

  @Test
  void complete_settings_are_accepted() {
    var properties = new MizanProperties("https://mizan.example", List.of("https://store.example/"), PAYMENT, OPERATION, GATEWAY, GATEWAY, HTTP, SETTLEMENT, FEES, STORE, LIMITS);

    assertThat(properties.publicUrl()).isEqualTo("https://mizan.example");
  }

  @Test
  void every_missing_setting_is_named_at_once() {
    var nothing = new GatewayProperties(null, " ", null);

    assertThatThrownBy(() -> new MizanProperties(null, List.of(), PAYMENT, OPERATION, nothing, nothing, HTTP, SETTLEMENT, FEES, STORE, LIMITS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MIZAN_PUBLIC_URL")
        .hasMessageContaining("MIZAN_RETURN_URL_PREFIXES")
        .hasMessageContaining("MIZAN_CARD_GATEWAY_URL")
        .hasMessageContaining("MIZAN_CARD_GATEWAY_KEY")
        .hasMessageContaining("MIZAN_CARD_GATEWAY_WEBHOOK_SECRET")
        .hasMessageContaining("MIZAN_PAY_LATER_URL")
        .hasMessageContaining("MIZAN_PAY_LATER_KEY")
        .hasMessageContaining("MIZAN_PAY_LATER_WEBHOOK_SECRET");
  }

  @Test
  void one_missing_setting_is_named_alone() {
    var noSecret = new GatewayProperties("https://gateway.example", "key", "");

    assertThatThrownBy(() -> new MizanProperties("https://mizan.example", List.of("https://store.example/"), PAYMENT, OPERATION, GATEWAY, noSecret, HTTP, SETTLEMENT, FEES, STORE, LIMITS))
        .hasMessageEndingWith("missing: MIZAN_PAY_LATER_WEBHOOK_SECRET");
  }

  @Test
  void a_store_address_needs_a_secret_to_sign_events_with() {
    var address = new StoreProperties("https://store.example/mizan-events", " ");

    assertThatThrownBy(() -> new MizanProperties("https://mizan.example", List.of("https://store.example/"), PAYMENT, OPERATION, GATEWAY, GATEWAY, HTTP, SETTLEMENT, FEES, address, LIMITS))
        .hasMessageEndingWith("missing: MIZAN_STORE_EVENT_SECRET");
  }

  @Test
  void the_quiet_time_must_be_longer_than_a_gateway_call_can_take() {
    var tooShort = new OperationProperties(Duration.ofSeconds(10), Duration.ofDays(30));

    assertThatThrownBy(() -> new MizanProperties("https://mizan.example", List.of("https://store.example/"), PAYMENT, tooShort, GATEWAY, GATEWAY, HTTP, SETTLEMENT, FEES, STORE, LIMITS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("quiet-time");
  }
}
