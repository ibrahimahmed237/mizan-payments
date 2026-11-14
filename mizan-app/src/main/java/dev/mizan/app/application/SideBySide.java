package dev.mizan.app.application;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Runs the pieces of one round side by side, at most {@code parallel} at a time, and returns when all are done.
 *
 * <p>A worker's piece is mostly waiting for a gateway or for the store, with no transaction open (rule R5). One
 * at a time, a slow gateway would hold up every payment behind it. Each piece must be safe to run next to any
 * other: the payments' locks see to that.
 */
final class SideBySide implements AutoCloseable {

  /** Null when the pieces run one after another, on the caller's own thread. */
  private final ExecutorService threads;

  SideBySide(String name, int parallel) {
    if (parallel < 1) {
      throw new IllegalArgumentException("At least one piece runs at a time.");
    }
    AtomicInteger number = new AtomicInteger();
    this.threads = parallel == 1 ? null : Executors.newFixedThreadPool(parallel, work -> {
      Thread thread = new Thread(work, name + "-" + number.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    });
  }

  /** @param piece the work on one item. It handles its own failures: one item's trouble must not stop the others */
  <T> void forEach(List<T> items, Consumer<T> piece) {
    if (threads == null) {
      items.forEach(piece);
      return;
    }
    List<Future<?>> running = items.stream().<Future<?>>map(item -> threads.submit(() -> piece.accept(item))).toList();
    for (Future<?> one : running) {
      try {
        one.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt(); // the service is stopping; what is still running ends by itself
        return;
      } catch (ExecutionException e) {
        throw new IllegalStateException("A piece of a round let a failure out.", e.getCause());
      }
    }
  }

  @Override
  public void close() {
    if (threads != null) {
      threads.shutdownNow();
    }
  }
}
