package dev.mizan.app.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mizan.app.application.Problem;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RequestHashTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static String hash(String method, String path, String body) {
    JsonNode json = body == null ? null : JSON.readTree(body.replace('\'', '"'));
    return RequestHash.of(method, path, json);
  }

  @Test
  void the_same_request_has_the_same_hash_whatever_the_order_of_its_fields_and_its_spaces() {
    String one = hash("POST", "/v1/payments", "{'amount':15000,'source':{'token':'t','type':'x'},'list':[1,2]}");
    String other = hash("POST", "/v1/payments", "{ 'list' : [1, 2], 'source' : {'type':'x', 'token':'t'}, 'amount' : 15000 }");

    assertThat(one).isEqualTo(other).matches("[0-9a-f]{64}");
  }

  @Test
  void another_value_field_method_or_path_is_another_request() {
    String base = hash("POST", "/v1/payments/1/refunds", "{'amount':5000}");

    assertThat(hash("POST", "/v1/payments/1/refunds", "{'amount':5001}")).isNotEqualTo(base);
    assertThat(hash("POST", "/v1/payments/1/refunds", "{'amount':'5000'}")).isNotEqualTo(base);
    assertThat(hash("POST", "/v1/payments/1/refunds", "{'amount':5000,'reason':'x'}")).isNotEqualTo(base);
    assertThat(hash("POST", "/v1/payments/2/refunds", "{'amount':5000}")).isNotEqualTo(base);
    assertThat(hash("POST", "/v1/payments/1/capture", "{'amount':5000}")).isNotEqualTo(base);
    assertThat(hash("PUT", "/v1/payments/1/refunds", "{'amount':5000}")).isNotEqualTo(base);
    assertThat(hash("POST", "/v1/payments/1/refunds", "{'list':[1,2]}")).isNotEqualTo(hash("POST", "/v1/payments/1/refunds", "{'list':[2,1]}"));
  }

  @Test
  void no_body_and_an_empty_object_are_the_same_request() {
    assertThat(hash("POST", "/v1/payments/1/cancel", null)).isEqualTo(hash("POST", "/v1/payments/1/cancel", "{}"));
  }

  @Test
  void a_key_is_needed_and_is_1_to_128_printable_characters() {
    assertThat(RequestHash.key("order-1042-try-1")).isEqualTo("order-1042-try-1");
    assertThat(RequestHash.key("k".repeat(128))).hasSize(128);
    assertThatThrownBy(() -> RequestHash.key(null)).isInstanceOfSatisfying(Problem.class, p -> assertThat(p.code()).isEqualTo("idempotency_key_required"));
    assertThatThrownBy(() -> RequestHash.key("  ")).isInstanceOfSatisfying(Problem.class, p -> assertThat(p.code()).isEqualTo("idempotency_key_required"));
    for (String bad : new String[] {"k".repeat(129), "has space", "tab\there", "مفتاح", "line\nbreak"}) {
      assertThatThrownBy(() -> RequestHash.key(bad)).isInstanceOfSatisfying(Problem.class, p -> assertThat(p.code()).isEqualTo("invalid_idempotency_key"));
    }
  }
}
