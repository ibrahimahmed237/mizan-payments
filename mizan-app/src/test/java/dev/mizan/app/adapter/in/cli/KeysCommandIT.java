package dev.mizan.app.adapter.in.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mizan.app.support.Api;
import dev.mizan.app.support.IntegrationTest;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/** Keys are made from the command line, never over the network. See docs/lld/running.md. */
class KeysCommandIT extends IntegrationTest {

  private final KeysCommand command = world.app.getBean(KeysCommand.class);

  /** Runs the command and returns what it printed. */
  private String run(String... arguments) {
    PrintStream terminal = System.out;
    var printed = new ByteArrayOutputStream();
    System.setOut(new PrintStream(printed, true, StandardCharsets.UTF_8));
    try {
      command.run(new DefaultApplicationArguments(arguments));
    } finally {
      System.setOut(terminal);
    }
    return printed.toString(StandardCharsets.UTF_8);
  }

  @Test
  void create_prints_a_key_that_works_and_revoke_ends_it() {
    String name = "cli-" + UUID.randomUUID();

    String printed = run("keys", "create", "--name=" + name, "--scopes=payments,backoffice");
    String key = printed.lines().filter(line -> line.startsWith("mzn_")).findFirst().orElseThrow();
    Api caller = api.withKey(key);

    assertThat(printed).contains("shown only now");
    assertThat(caller.get("/v1/payments").status()).isEqualTo(200);
    assertThat(caller.get("/v1/ledger/balances").status()).isNotIn(401, 403); // the backoffice scope too

    assertThat(run("keys", "revoke", "--name=" + name)).contains("Revoked");
    assertThat(caller.get("/v1/payments").status()).isEqualTo(401);
    assertThat(run("keys", "revoke", "--name=" + name)).contains("No active key");
  }

  @Test
  void a_command_that_is_not_complete_prints_how_to_use_it_and_makes_nothing() {
    long before = jdbc.sql("SELECT count(*) FROM api_keys").query(Long.class).single();

    assertThat(run("keys")).contains("Usage");
    assertThat(run("keys", "create", "--name=x")).contains("Usage");
    assertThat(run("keys", "create", "--scopes=payments")).contains("Usage");
    assertThat(run("keys", "delete", "--name=x")).contains("Usage");

    assertThat(jdbc.sql("SELECT count(*) FROM api_keys").query(Long.class).single()).isEqualTo(before);
  }

  @Test
  void a_normal_start_of_the_service_runs_no_command() {
    assertThat(run()).isEmpty();
    assertThat(run("--server.port=0")).isEmpty();
  }
}
