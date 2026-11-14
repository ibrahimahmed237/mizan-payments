package dev.mizan.app.adapter.in.web;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * A token bucket per caller: a caller may make {@code burst} requests at once, and gets {@code perSecond} new ones
 * each second. It runs on real time, not on Mizan's clock: a limit is about load, not about business time.
 */
final class RateLimiter {

  /** Past this many callers, the buckets are forgotten and start full again: memory cannot grow without end. */
  private static final int MAX_CALLERS = 100_000;

  private final double perSecond;
  private final int burst;
  private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

  RateLimiter(double perSecond, int burst) {
    if (perSecond <= 0 || burst < 1) {
      throw new IllegalArgumentException("A limit has a rate above zero and a burst of at least one.");
    }
    this.perSecond = perSecond;
    this.burst = burst;
  }

  /** @return 0 when the request may go on; otherwise the seconds to wait, at least 1 */
  long take(String caller) {
    if (buckets.size() > MAX_CALLERS) {
      buckets.clear();
    }
    return buckets.computeIfAbsent(caller, key -> new Bucket(burst, System.nanoTime())).take(perSecond, burst, System.nanoTime());
  }

  /** One caller's tokens. */
  private static final class Bucket {
    private double tokens;
    private long lastNanos;

    Bucket(double tokens, long now) {
      this.tokens = tokens;
      this.lastNanos = now;
    }

    synchronized long take(double perSecond, int burst, long now) {
      tokens = Math.min(burst, tokens + (now - lastNanos) / 1e9 * perSecond);
      lastNanos = now;
      if (tokens >= 1) {
        tokens -= 1;
        return 0;
      }
      return Math.max(1, (long) Math.ceil((1 - tokens) / perSecond));
    }
  }
}
