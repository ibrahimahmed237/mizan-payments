package dev.mizan.app.application.port;

import java.util.function.Supplier;

/**
 * Runs work in one database transaction. Use cases open transactions only through this, so it is plain in the code
 * where a transaction begins and ends, and a gateway call can stand between two of them (rule R5).
 */
public interface Transactions {

  <T> T run(Supplier<T> work);

  default void run(Runnable work) {
    run(() -> {
      work.run();
      return null;
    });
  }
}
