package dev.mizan.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mizan.app.config.MizanProperties.GatewayProperties;
import dev.mizan.app.config.MizanProperties.HttpProperties;
import dev.mizan.app.config.MizanProperties.OperationProperties;
import dev.mizan.app.config.MizanProperties.PaymentProperties;
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

  @Test
  void complete_settings_are_accepted() {
    var properties = new MizanProperties("https://mizan.example", List.of("https://store.example/"), PAYMENT, OPERATION, GATEWAY, GATEWAY, HTTP);

    assertThat(properties.publicUrl()).isEqualTo("https://mizan.example");
  }

  @Test
  void every_missing_setting_is_named_at_once() {
    var nothing = new GatewayProperties(null, " ", null);

    assertThatThrownBy(() -> new MizanProperties(null, List.of(), PAYMENT, OPERATION, nothing, nothing, HTTP))
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

    assertThatThrownBy(() -> new MizanProperties("https://mizan.example", List.of("https://store.example/"), PAYMENT, OPERATION, GATEWAY, noSecret, HTTP))
        .hasMessageEndingWith("missing: MIZAN_PAY_LATER_WEBHOOK_SECRET");
  }

  @Test
  void the_quiet_time_must_be_longer_than_a_gateway_call_can_take() {
    var tooShort = new OperationProperties(Duration.ofSeconds(10), Duration.ofDays(30));

    assertThatThrownBy(() -> new MizanProperties("https://mizan.example", List.of("https://store.example/"), PAYMENT, tooShort, GATEWAY, GATEWAY, HTTP))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("quiet-time");
  }
}
