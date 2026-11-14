package dev.mizan.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** The settings as Spring reads them from text, with their defaults. */
class MizanPropertiesBindingTest {

  private static MizanProperties bind(Map<String, String> extra) {
    Map<String, String> settings = new HashMap<>(Map.of(
        "mizan.public-url", "https://mizan.example",
        "mizan.return-url-prefixes", "https://store.example/a,https://store.example/b",
        "mizan.card-gateway.url", "https://card.example", "mizan.card-gateway.key", "k", "mizan.card-gateway.webhook-secret", "s",
        "mizan.pay-later.url", "https://later.example", "mizan.pay-later.key", "k", "mizan.pay-later.webhook-secret", "s"));
    settings.putAll(extra);
    return new Binder(new MapConfigurationPropertySource(settings)).bind("mizan", MizanProperties.class).get();
  }

  @Test
  void the_defaults_are_the_documented_ones() {
    MizanProperties properties = bind(Map.of());

    assertThat(properties.returnUrlPrefixes()).containsExactly("https://store.example/a", "https://store.example/b");
    assertThat(properties.payment().maxAmount()).isEqualTo(10_000_000);
    assertThat(properties.payment().cardWait()).hasMinutes(30);
    assertThat(properties.payment().payLaterWait()).hasHours(2);
    assertThat(properties.payment().holdWindow()).hasDays(7);
    assertThat(properties.operation().quietTime()).hasMinutes(1);
    assertThat(properties.operation().idempotencyKeysKept()).hasDays(30);
    assertThat(properties.http().timeout()).hasSeconds(10);
    assertThat(properties.settlement().missingAfter()).hasDays(3);
    assertThat(properties.fees().card()).isEqualTo(new MizanProperties.FeeProperties(250, 100, 1500));
    assertThat(properties.fees().payLater()).isEqualTo(new MizanProperties.FeeProperties(400, 0, 1500));
    assertThat(properties.workers()).isEqualTo(new MizanProperties.WorkersProperties(16, 8));
  }

  @Test
  void the_workers_other_settings_do_not_get_in_the_way_of_how_many_run_side_by_side() {
    MizanProperties properties = bind(Map.of(
        "mizan.workers.enabled", "false", "mizan.workers.sync-every", "5s", "mizan.workers.sync-parallel", "32"));

    assertThat(properties.workers()).isEqualTo(new MizanProperties.WorkersProperties(32, 8));
  }

  @Test
  void a_fee_rule_can_be_set_from_the_contract() {
    MizanProperties properties = bind(Map.of(
        "mizan.fees.card.percent-bps", "199", "mizan.fees.card.fixed", "50", "mizan.fees.card.vat-bps", "1500",
        "mizan.settlement.missing-after", "5d"));

    assertThat(properties.fees().card()).isEqualTo(new MizanProperties.FeeProperties(199, 50, 1500));
    assertThat(properties.fees().payLater()).isEqualTo(new MizanProperties.FeeProperties(400, 0, 1500)); // the other keeps its example
    assertThat(properties.settlement().missingAfter()).hasDays(5);
  }
}
