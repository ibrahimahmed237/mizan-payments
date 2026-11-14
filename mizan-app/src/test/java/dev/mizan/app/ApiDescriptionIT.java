package dev.mizan.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.app.support.Api.Answer;
import dev.mizan.app.support.IntegrationTest;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/** The API description says exactly what the service answers: no address missing, none made up. */
class ApiDescriptionIT extends IntegrationTest {

  @Test
  void every_address_the_service_answers_is_described_and_every_described_address_is_answered() {
    Answer served = api.withKey(null).get("/v3/api-docs");
    Map<String, Object> description = new Yaml().load(served.raw().body());

    Set<String> described = new TreeSet<>();
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) description.get("paths");
    paths.forEach((path, methods) -> methods.keySet().forEach(method -> described.add(method.toUpperCase(Locale.ROOT) + " " + path)));

    Set<String> answered = new TreeSet<>();
    world.app.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class).getHandlerMethods().keySet().forEach(mapping ->
        mapping.getPathPatternsCondition().getPatternValues().forEach(path -> mapping.getMethodsCondition().getMethods()
            .forEach(method -> answered.add(method.name() + " " + path))));
    answered.removeIf(route -> route.contains("/error"));

    assertThat(served.status()).isEqualTo(200);
    assertThat(served.header("Content-Type")).startsWith("application/yaml");
    assertThat(described).isEqualTo(answered);
    assertThat(description.get("openapi")).isEqualTo("3.1.0");
  }

  @Test
  void the_description_of_a_payment_names_every_field_the_api_returns() {
    Map<String, Object> description = new Yaml().load(api.withKey(null).get("/v3/api-docs").raw().body());
    @SuppressWarnings("unchecked")
    Map<String, Object> payment = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) description.get("components")).get("schemas")).get("Payment");
    @SuppressWarnings("unchecked")
    Set<String> fields = ((Map<String, Object>) payment.get("properties")).keySet();

    Set<String> returned = new TreeSet<>();
    pay(newOrder(), 15_000, "1111", "AUTO").json().propertyNames().forEach(returned::add);

    assertThat(new TreeSet<>(fields)).isEqualTo(returned);
  }
}
