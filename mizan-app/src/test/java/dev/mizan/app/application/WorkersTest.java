package dev.mizan.app.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WorkersTest {

  @Test
  void one_at_a_time_and_sixty_four_at_a_time_are_the_ends() {
    assertThat(new Workers(1, 64).syncParallel()).isEqualTo(1);
    assertThat(new Workers(64, 1).sendParallel()).isEqualTo(1);
  }

  @ParameterizedTest
  @CsvSource({"0,8", "65,8", "16,0", "16,65", "-1,8"})
  void outside_them_the_service_does_not_start_and_says_which_settings(int sync, int send) {
    assertThatThrownBy(() -> new Workers(sync, send))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mizan.workers.sync-parallel")
        .hasMessageContaining("mizan.workers.send-parallel");
  }
}
