package dev.mizan.app.adapter.out.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.app.application.port.GatewayClient;
import dev.mizan.app.application.port.GatewayClient.Create;
import dev.mizan.app.application.port.GatewayResult;
import dev.mizan.app.application.port.GatewayResult.Done;
import dev.mizan.core.payment.Gateway;
import dev.mizan.core.payment.GatewayStatus;
import dev.mizan.core.payment.Method;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The card gateway client against a real gateway's test mode, to check what the simulator assumes (assumptions A1 to
 * A3 of the design). It runs only with test keys in the environment, and is skipped without them:
 *
 * <pre>
 *   MIZAN_REAL_CARD_URL=https://.../v1  MIZAN_REAL_CARD_KEY=sk_test_...  MIZAN_REAL_CARD_TOKEN=token_...  ./mvnw -pl mizan-app test -Dtest=RealGatewayTest
 * </pre>
 *
 * The token is made from a test card on the gateway's own page. Keys come from the environment only; never put one in a file.
 */
@EnabledIfEnvironmentVariable(named = "MIZAN_REAL_CARD_KEY", matches = ".+")
class RealGatewayTest {

  private final GatewayClient card = new HttpGateways(
      new HttpGateways.Access(System.getenv("MIZAN_REAL_CARD_URL"), System.getenv("MIZAN_REAL_CARD_KEY")),
      new HttpGateways.Access("http://localhost:1", "unused"),
      Duration.ofSeconds(5), Duration.ofSeconds(20))
      .of(Gateway.CARD_GATEWAY);

  @Test
  void a_payment_the_gateway_does_not_have_is_not_found() {
    GatewayResult result = card.fetch(UUID.randomUUID().toString());

    assertThat(result).isInstanceOf(Done.class);
    assertThat(((Done) result).snapshot().status()).isEqualTo(GatewayStatus.NOT_FOUND);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "MIZAN_REAL_CARD_TOKEN", matches = ".+")
  void a_payment_made_with_mizans_id_is_made_once_and_found_by_that_id() {
    UUID id = UUID.randomUUID();
    var request = new Create(id, "mizan-conformance-" + id, 100, "SAR", Method.CARD, System.getenv("MIZAN_REAL_CARD_TOKEN"), true,
        "https://example.com/return", "Mizan conformance check");

    GatewayResult first = card.create(request);
    // A1: the same id again makes no second payment. The answer may be that payment, or a refusal of the used token.
    card.create(request);
    GatewayResult fetched = card.fetch(id.toString());

    assertThat(first).isInstanceOf(Done.class);
    assertThat(((Done) first).gatewayPaymentId()).isEqualTo(id.toString());
    assertThat(((Done) fetched).snapshot().amount()).isEqualTo(100);
    if (((Done) fetched).snapshot().status() == GatewayStatus.AUTHORIZED) {
      assertThat(card.cancel(id.toString())).isInstanceOf(Done.class); // leave nothing held
    }
  }
}
