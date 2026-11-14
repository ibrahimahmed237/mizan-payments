package dev.mizan.core.fees;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FeeRuleTest {

  @ParameterizedTest(name = "{0} halalas at {1} bps + {2}, VAT {3} bps -> {4}")
  @CsvSource({
    // gross, percent, fixed, VAT, fee
    "10000, 250, 100, 1500, 403",   // 250 + 100 = 350; VAT 52.5 -> 53
    "10000, 250,   0,    0, 250",
    "10000,   0, 100,    0, 100",
    "10000,   0,   0, 1500,   0",
    "  100, 250,   0,    0,   3",   // 2.5 rounds up
    "   99, 250,   0,    0,   2",   // 2.475 rounds down
    "   20, 250,   0,    0,   1",   // 0.5 rounds up
    "   19, 250,   0,    0,   0",   // 0.475 rounds down
    "15000, 100,   0, 1500, 173",   // 150; VAT 22.5 -> 23
    "    1, 10000, 0, 10000,  2",
  })
  void the_fee_is_the_rounded_percentage_plus_the_fixed_part_plus_vat(long gross, int percent, long fixed, int vat, long fee) {
    assertThat(new FeeRule(percent, fixed, vat).feeFor(gross)).isEqualTo(fee);
  }

  @Test
  void a_fee_is_for_an_amount_above_zero() {
    FeeRule rule = new FeeRule(250, 100, 1500);

    assertThatThrownBy(() -> rule.feeFor(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> rule.feeFor(-100)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_rule_has_sane_parts() {
    assertThatThrownBy(() -> new FeeRule(-1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FeeRule(10_001, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FeeRule(0, -1, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FeeRule(0, 0, -1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FeeRule(0, 0, 10_001)).isInstanceOf(IllegalArgumentException.class);
  }
}
