package edu.jala.ygocards.upstream;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * A token bucket that refills continuously rather than on a clock tick.
 *
 * <p>The distinction is the whole point of this class. An earlier version released a batch of
 * permits once a second while a caller waited at most half a second, so whether a waiting request
 * was served depended on where the burst happened to land relative to that tick. Measured, the
 * same page of twenty four images was refused eight times in one run and four in the next. That
 * is not a capacity problem, it is a coin flip, and a system that fails at random is harder to
 * operate than one that fails predictably: it cannot be reproduced, it cannot be tuned against,
 * and the same action succeeding on Tuesday and failing on Wednesday teaches an operator nothing.
 *
 * <p>Here the permit count is a function of elapsed time, computed at the moment it is asked for.
 * A caller that cannot be served immediately is told exactly how long it must wait, and that
 * answer is the same every time for the same position in the queue. A caller that would have to
 * wait longer than its budget allows is refused deterministically instead of hopefully.
 *
 * <p>Reservations go negative on purpose. When a caller takes a permit it does not yet have, the
 * balance drops below zero, so the next caller computes a longer wait and the one after that a
 * longer one still. That is what turns a burst into an orderly queue rather than a scramble.
 */
final class TokenBucket {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final double permitsPerSecond;
    private final double capacity;

    private double available;
    private long lastRefillNanos;

    TokenBucket(int permitsPerSecond) {
        this.permitsPerSecond = permitsPerSecond;
        this.capacity = permitsPerSecond;
        this.available = permitsPerSecond;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Takes a permit, sleeping for its turn when one is not free yet.
     *
     * @return how the attempt went, including the wait that was needed even when it was refused,
     *         so that the caller can tell a client when to come back
     */
    Outcome acquire(Duration maxWait) {
        Reservation reservation = reserve(maxWait.toNanos());

        if (!reservation.granted()) {
            return new Outcome(false, reservation.waitNanos());
        }
        if (reservation.waitNanos() > 0) {
            try {
                TimeUnit.NANOSECONDS.sleep(reservation.waitNanos());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Outcome(false, reservation.waitNanos());
            }
        }
        return new Outcome(true, reservation.waitNanos());
    }

    private synchronized Reservation reserve(long maxWaitNanos) {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) / (double) NANOS_PER_SECOND;
        lastRefillNanos = now;
        available = Math.min(capacity, available + elapsedSeconds * permitsPerSecond);

        if (available >= 1.0) {
            available -= 1.0;
            return new Reservation(true, 0L);
        }

        double deficit = 1.0 - available;
        long waitNanos = (long) (deficit / permitsPerSecond * NANOS_PER_SECOND);

        if (waitNanos > maxWaitNanos) {
            // Refused without reserving, so a caller that gives up does not make the queue longer
            // for everyone behind it.
            return new Reservation(false, waitNanos);
        }

        available -= 1.0;
        return new Reservation(true, waitNanos);
    }

    /** What happened, and how long a permit was or would have been away. */
    record Outcome(boolean granted, long waitNanos) {

        long waitMillis() {
            return waitNanos / 1_000_000L;
        }

        /** Whole seconds to put in a {@code Retry-After} header, never below one. */
        long retryAfterSeconds() {
            return Math.max(1L, (waitNanos + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND);
        }
    }

    private record Reservation(boolean granted, long waitNanos) {
    }
}
