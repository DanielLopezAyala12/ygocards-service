package edu.jala.ygocards.upstream;

import edu.jala.ygocards.config.UpstreamProperties;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Keeps outbound traffic under the ceiling the upstream provider publishes.
 *
 * <p>The provider allows 20 requests per second and blocks the caller for an hour beyond that.
 * Respecting that is the consumer's job: the provider enforces it by blocking, and only the
 * client can avoid triggering it. A permit is released back up to the configured rate once a
 * second, so a burst is smoothed instead of being sent all at once.
 *
 * <p>Waiting is bounded on purpose. A caller that queued indefinitely would hold a request
 * thread for as long as the burst lasted, so a spike in traffic would exhaust the thread pool
 * and take the service down. Giving up after a short wait and answering 503 with
 * {@code Retry-After} turns that outage into a delay the caller can act on.
 *
 * <h2>Known limitation</h2>
 * This limiter counts requests inside one process. Three replicas configured at eight requests
 * per second would together send twenty four and trigger the block. Sharing a budget across
 * replicas needs an external coordinator, which is recorded as a deferred improvement rather
 * than claimed as solved.
 */
@Component
public class UpstreamRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(UpstreamRateLimiter.class);

    private final int permitsPerSecond;
    private final long waitMillis;
    private final Semaphore permits;
    private final ScheduledExecutorService refill;

    public UpstreamRateLimiter(UpstreamProperties properties) {
        this.permitsPerSecond = properties.rateLimitPerSecond();
        this.waitMillis = properties.rateLimitWait().toMillis();
        this.permits = new Semaphore(permitsPerSecond);
        this.refill = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "upstream-rate-limiter");
            thread.setDaemon(true);
            return thread;
        });
        this.refill.scheduleAtFixedRate(this::topUp, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Takes one permit, waiting up to the configured limit.
     *
     * @return true when the call may proceed, false when the caller should be told to retry
     */
    public boolean tryAcquire(String what) {
        try {
            boolean granted = permits.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
            if (granted) {
                log.debug("Rate limiter granted a permit for {} ({} of {} left)",
                        what, permits.availablePermits(), permitsPerSecond);
            } else {
                log.warn("Rate limiter denied {} after waiting {}ms: {} requests/second ceiling reached",
                        what, waitMillis, permitsPerSecond);
            }
            return granted;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Rate limiter wait for {} was interrupted", what);
            return false;
        }
    }

    /** How long a denied caller should be told to wait, in whole seconds, never below one. */
    public long retryAfterSeconds() {
        return 1;
    }

    private void topUp() {
        int missing = permitsPerSecond - permits.availablePermits();
        if (missing > 0) {
            permits.release(missing);
        }
    }

    @PreDestroy
    void shutdown() {
        refill.shutdownNow();
    }
}
