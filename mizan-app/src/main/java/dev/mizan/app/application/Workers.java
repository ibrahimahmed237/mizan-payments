package dev.mizan.app.application;

/**
 * How the workers run their rounds. See docs/lld/workers.md, "How much one instance can do".
 *
 * @param syncParallel how many payments the sync worker looks at side by side
 * @param sendParallel how many events the event sender sends side by side
 */
public record Workers(int syncParallel, int sendParallel) {

  public Workers {
    if (syncParallel < 1 || syncParallel > 64 || sendParallel < 1 || sendParallel > 64) {
      throw new IllegalArgumentException("mizan.workers.sync-parallel and mizan.workers.send-parallel are from 1 to 64.");
    }
  }
}
