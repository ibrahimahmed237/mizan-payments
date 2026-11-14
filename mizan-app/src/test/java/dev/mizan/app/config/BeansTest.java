package dev.mizan.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class BeansTest {

  @Test
  void the_clock_gives_the_time_now_cut_to_what_the_database_keeps() {
    Instant before = Instant.now().minusSeconds(1);

    Instant now = new Beans().clock().instant();

    assertThat(now).isBetween(before, Instant.now().plusSeconds(1));
    assertThat(now.getNano() % 1_000).isZero(); // microseconds: a time read back from the database equals the time written
  }
}
