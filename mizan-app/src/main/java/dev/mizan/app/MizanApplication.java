package dev.mizan.app;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Mizan: the payments service of one online store.
 *
 * <p>Started with no arguments, it serves the API. Started with {@code keys ...}, it runs that one command against
 * the database and stops: see {@code KeysCommand}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MizanApplication {

  public static void main(String[] args) {
    boolean command = args.length > 0 && args[0].equals("keys");
    var application = new SpringApplication(MizanApplication.class);
    if (command) {
      application.setWebApplicationType(WebApplicationType.NONE); // no API and no workers: only the command
      application.setDefaultProperties(Map.of("mizan.workers.enabled", "false"));
    }
    ConfigurableApplicationContext context = application.run(args);
    if (command) {
      System.exit(SpringApplication.exit(context));
    }
  }
}
