package dev.mizan.app.application.port;

import java.time.Instant;

/** Deletes what is past its time. Financial records are never deleted. Each call deletes at most one batch. */
public interface Housekeeping {

  /** @return how many were deleted */
  int deleteIdempotencyKeysExpiredBy(Instant now, int batch);

  int deleteWebhookEventsReceivedBefore(Instant before, int batch);

  /** Only events that were delivered, or kept and never sent. A waiting or failed event stays. */
  int deleteDoneEventsCreatedBefore(Instant before, int batch);
}
