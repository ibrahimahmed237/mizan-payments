package dev.mizan.simulator;

import dev.mizan.core.payment.Gateway;
import java.net.URI;
import java.time.Clock;

/** Runs the simulator as a program: {@code java -jar mizan-simulator.jar}. Settings come from the environment. */
public final class SimulatorMain {

  private SimulatorMain() {}

  public static void main(String[] args) throws InterruptedException {
    int port = Integer.parseInt(env("SIMULATOR_PORT", "8090"));
    var simulator = new Simulator(Clock.systemUTC(), Simulator.Config.defaults());
    String cardWebhooks = env("SIMULATOR_CARD_WEBHOOK_URL", "");
    String payLaterWebhooks = env("SIMULATOR_PAY_LATER_WEBHOOK_URL", "");
    if (!cardWebhooks.isEmpty()) {
      simulator.webhookTarget(Gateway.CARD_GATEWAY, URI.create(cardWebhooks));
    }
    if (!payLaterWebhooks.isEmpty()) {
      simulator.webhookTarget(Gateway.PAY_LATER, URI.create(payLaterWebhooks));
    }
    SimulatorServer server = SimulatorServer.start(simulator, port);
    System.out.println("Mizan simulator on " + server.baseUrl() + ". A test tool: never put it on a public address.");
    Thread.currentThread().join();
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
