package dev.mizan.app.application.port;

import java.util.UUID;

/** Where events for the store are sent. */
public interface StoreEndpoint {

  /** @param error why it was not delivered; null when it was */
  record Delivery(boolean delivered, String error) {}

  /** False when no address is set. Events are then kept and not sent. */
  boolean configured();

  /**
   * Sends one event, once.
   *
   * @param timestamp seconds since 1970, signed with the body so a captured request cannot be replayed later
   */
  Delivery send(UUID eventId, long timestamp, String body);
}
