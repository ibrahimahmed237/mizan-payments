package dev.mizan.app.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReturnUrlsTest {

  private static final List<String> PREFIXES = List.of("https://store.example/orders", "https://shop.example/");

  @ParameterizedTest
  @ValueSource(strings = {
      "https://store.example/orders",
      "https://store.example/orders/1042/paid",
      "https://store.example/orders?id=1042",
      "https://store.example/orders#done",
      "https://shop.example/",
      "https://shop.example/anything/at/all?x=1",
  })
  void an_address_under_a_configured_prefix_is_allowed(String url) {
    assertThat(ReturnUrls.allowed(url, PREFIXES)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "https://store.example/ordersX",                 // the prefix must end at a border
      "https://store.example.evil.example/orders/1",   // another host that starts the same
      "https://store.example@evil.example/orders/1",   // the "host" is really a user name
      "https://evil.example/https://store.example/orders",
      "http://store.example/orders/1",                 // another scheme than the configured one
      "ftp://shop.example/file",
      "https://shop.example",                          // shorter than the prefix
      "HTTPS://STORE.EXAMPLE/orders/1",                // the prefix is compared as written
      "javascript:alert(1)",
      "/orders/1",                                     // no host
      "https://store.example/orders/a b",              // not a well-formed address
      "",
  })
  void anything_else_is_refused(String url) {
    assertThat(ReturnUrls.allowed(url, PREFIXES)).isFalse();
  }

  @Test
  void no_address_too_long_an_address_and_no_prefixes_are_refused() {
    assertThat(ReturnUrls.allowed(null, PREFIXES)).isFalse();
    assertThat(ReturnUrls.allowed("https://shop.example/" + "a".repeat(2_048), PREFIXES)).isFalse();
    assertThat(ReturnUrls.allowed("https://shop.example/" + "a".repeat(2_027), PREFIXES)).isTrue(); // exactly 2,048 characters
    assertThat(ReturnUrls.allowed("https://store.example/orders/1", List.of())).isFalse();
    assertThat(ReturnUrls.allowed("https://store.example/orders/1", List.of(""))).isFalse(); // an empty prefix allows nothing
  }

  @Test
  void the_payment_id_is_added_before_any_fragment() {
    assertThat(ReturnUrls.withPaymentId("https://store.example/orders/1", "p1")).isEqualTo("https://store.example/orders/1?payment_id=p1");
    assertThat(ReturnUrls.withPaymentId("https://store.example/orders?id=1", "p1")).isEqualTo("https://store.example/orders?id=1&payment_id=p1");
    assertThat(ReturnUrls.withPaymentId("https://store.example/orders/1#paid", "p1")).isEqualTo("https://store.example/orders/1?payment_id=p1#paid");
    assertThat(ReturnUrls.withPaymentId("https://store.example/o?a=1#x?y", "p1")).isEqualTo("https://store.example/o?a=1&payment_id=p1#x?y");
  }
}
