package dev.mizan.app.config;

import dev.mizan.app.adapter.in.web.WebhookReader;
import dev.mizan.app.adapter.out.gateway.HttpGateways;
import dev.mizan.app.application.Settings;
import dev.mizan.app.application.port.Gateways;
import dev.mizan.core.fees.FeeRule;
import dev.mizan.core.payment.Gateway;
import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Wires what Spring cannot find by itself. */
@Configuration
@EnableScheduling
class Beans {

  /**
   * The clock. Tests put their own in its place and move it by hand. Times are cut to microseconds, which is what
   * the database keeps: a time read back is then equal to the time that was written.
   */
  @Bean
  @ConditionalOnMissingBean
  InstantSource clock() {
    return () -> Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  @Bean
  Settings settings(MizanProperties properties) {
    return new Settings(
        properties.payment().maxAmount(),
        properties.payment().cardWait(),
        properties.payment().payLaterWait(),
        properties.payment().holdWindow(),
        properties.payment().lateCapture(),
        properties.operation().quietTime(),
        properties.returnUrlPrefixes(),
        stripSlash(properties.publicUrl()),
        properties.operation().idempotencyKeysKept(),
        Map.of(
            Gateway.CARD_GATEWAY, feeRule(properties.fees().card()),
            Gateway.PAY_LATER, feeRule(properties.fees().payLater())),
        properties.settlement().missingAfter());
  }

  @Bean
  Gateways gateways(MizanProperties properties) {
    return new HttpGateways(
        new HttpGateways.Access(properties.cardGateway().url(), properties.cardGateway().key()),
        new HttpGateways.Access(properties.payLater().url(), properties.payLater().key()),
        properties.http().connectTimeout(),
        properties.http().timeout());
  }

  @Bean
  WebhookReader webhookReader(MizanProperties properties) {
    return new WebhookReader(new WebhookReader.Secrets(properties.cardGateway().webhookSecret(), properties.payLater().webhookSecret()));
  }

  private static FeeRule feeRule(MizanProperties.FeeProperties fee) {
    return new FeeRule(fee.percentBps(), fee.fixed(), fee.vatBps());
  }

  private static String stripSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
