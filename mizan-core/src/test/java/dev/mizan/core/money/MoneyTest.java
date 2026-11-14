package dev.mizan.core.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

  @ParameterizedTest
  @CsvSource({"150.00,15000", "150,15000", "0.05,5", "0,0", "' 12.30 ',1230", "999999999999999.99,99999999999999999"})
  void reads_riyals_exactly(String text, long halalas) {
    assertThat(Money.parseRiyals(text)).isEqualTo(halalas);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "1.5", "1.505", "-1.00", "1,00", "1e2", "abc", "1.", ".50", "1 000.00", "1234567890123456.00"})
  void refuses_text_that_is_not_whole_halalas(String text) {
    assertThatThrownBy(() -> Money.parseRiyals(text)).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @CsvSource({"15000,150.00", "5,0.05", "0,0.00", "-250,-2.50", "101,1.01"})
  void writes_halalas_as_riyals(long halalas, String text) {
    assertThat(Money.formatRiyals(halalas)).isEqualTo(text);
  }

  @Test
  void reading_what_was_written_gives_the_same_amount() {
    for (long halalas : new long[] {0, 1, 99, 100, 101, 15_000, 9_999_999}) {
      assertThat(Money.parseRiyals(Money.formatRiyals(halalas))).isEqualTo(halalas);
    }
  }

  @Test
  void guards_refuse_wrong_signs() {
    assertThat(Money.requireNotNegative(0, "x")).isZero();
    assertThat(Money.requirePositive(1, "x")).isEqualTo(1);
    assertThatThrownBy(() -> Money.requireNotNegative(-1, "fee")).hasMessageContaining("fee");
    assertThatThrownBy(() -> Money.requirePositive(0, "amount")).hasMessageContaining("amount");
  }
}
