package dev.mizan.app.application.model;

/** Who started an operation. */
public enum Origin {
  /** The store asked for it through the API. */
  API,
  /** Mizan started it by itself: a follow-up. */
  SYSTEM,
  /** It was done at the gateway, outside Mizan, and recorded after the fact. */
  GATEWAY
}
