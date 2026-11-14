package dev.mizan.app.adapter.in.cli;

import dev.mizan.app.application.ApiKeyService;
import java.util.List;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Makes and revokes API keys from the command line. There is no API for this on purpose: nobody can make a key
 * over the network.
 *
 * <pre>
 *   mizan keys create --name=website --scopes=payments
 *   mizan keys revoke --name=website
 * </pre>
 */
@Component
class KeysCommand implements ApplicationRunner {

  private final ApiKeyService keys;

  KeysCommand(ApiKeyService keys) {
    this.keys = keys;
  }

  @Override
  public void run(ApplicationArguments arguments) {
    List<String> words = arguments.getNonOptionArgs();
    if (words.isEmpty() || !words.get(0).equals("keys")) {
      return; // a normal start of the service
    }
    String name = option(arguments, "name");
    if (words.size() == 2 && words.get(1).equals("create") && name != null && option(arguments, "scopes") != null) {
      String key = keys.create(name, Set.of(option(arguments, "scopes").split(",")));
      print("API key for '" + name + "'. It is shown only now; keep it safe:");
      print(key);
    } else if (words.size() == 2 && words.get(1).equals("revoke") && name != null) {
      print(keys.revoke(name) ? "Revoked the keys named '" + name + "'." : "No active key is named '" + name + "'.");
    } else {
      print("Usage: keys create --name=<name> --scopes=payments,backoffice | keys revoke --name=<name>");
    }
  }

  private static String option(ApplicationArguments arguments, String name) {
    List<String> values = arguments.getOptionValues(name);
    return values == null || values.isEmpty() ? null : values.get(0);
  }

  /** The command's answer is for the terminal, not for the log: a key must never be in a log. */
  private static void print(String line) {
    System.out.println(line);
  }
}
