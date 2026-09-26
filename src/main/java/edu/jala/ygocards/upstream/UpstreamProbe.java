package edu.jala.ygocards.upstream;

import com.fasterxml.jackson.annotation.JsonProperty;
import edu.jala.ygocards.config.UpstreamProperties;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Decides whether the upstream API is reachable, and remembers the answer for a while.
 *
 * <p>The result is cached on purpose. A monitor polling the upstream health endpoint once a
 * second would otherwise spend the whole outbound request budget on health checks and trigger
 * the hour-long block that the endpoint exists to detect. With a time to live, the cost of
 * being monitored stays constant no matter how often the endpoint is called, and the response
 * reports how old the answer is so that nobody mistakes a cached result for a live one.
 *
 * <h2>Only one caller probes at a time</h2>
 * Caching alone is not enough. If several requests arrive at the moment the cached result
 * expires, every one of them would find it stale and every one would call the upstream, which
 * is exactly the burst the cache exists to prevent. One caller takes the lock and performs the
 * call; the others are handed the previous result immediately, marked {@code stale} so the
 * answer stays honest. Only a caller arriving before any result exists at all waits for the
 * probe in flight, because there is nothing older to give it.
 *
 * <h2>The probe counts against the outbound budget, but is never refused by it</h2>
 * Every call this service makes to the provider goes through the same limiter, including this
 * one. Leaving one route uncounted would make the claim that outbound traffic is controlled
 * false in a small way, and a claim that is false in a small way is still false.
 *
 * <p>The refusal path is different from the one user requests take, though. A user request that
 * cannot get a permit is answered with 503, because the caller can retry. A health probe that
 * cannot get a permit must not fail, because failing it would report the upstream as unreachable
 * on the strength of our own ceiling, and an orchestrator acting on that would restart a healthy
 * process. A self inflicted outage is a worse outcome than a slightly stale answer, so a refused
 * probe returns the last known result marked {@code stale} instead. Only when no result has ever
 * been obtained is the honest answer {@code unknown}, which is neither up nor down.
 */
@Component
public class UpstreamProbe {

    private static final Logger log = LoggerFactory.getLogger(UpstreamProbe.class);

    private final RestClient restClient;
    private final UpstreamProperties properties;
    private final UpstreamRateLimiter rateLimiter;
    private final AtomicReference<Result> last = new AtomicReference<>();
    private final ReentrantLock probeLock = new ReentrantLock();

    public UpstreamProbe(
            RestClient upstreamRestClient,
            UpstreamProperties properties,
            UpstreamRateLimiter rateLimiter) {
        this.restClient = upstreamRestClient;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
    }

    /** Returns a usable probe result, calling the upstream only when one caller needs to. */
    public Outcome current() {
        Result cached = last.get();
        if (cached != null && !isExpired(cached)) {
            return new Outcome(cached, Source.CACHE);
        }

        if (probeLock.tryLock()) {
            try {
                Result refreshedMeanwhile = last.get();
                if (refreshedMeanwhile != null && !isExpired(refreshedMeanwhile)) {
                    return new Outcome(refreshedMeanwhile, Source.CACHE);
                }
                return probeUnderLock();
            } finally {
                probeLock.unlock();
            }
        }

        Result stale = last.get();
        if (stale != null) {
            log.debug("Probe already in flight, answering from the previous result");
            return new Outcome(stale, Source.STALE);
        }

        // Nothing has ever been probed, so there is no older answer to hand back.
        probeLock.lock();
        try {
            Result afterWaiting = last.get();
            if (afterWaiting != null) {
                return new Outcome(afterWaiting, Source.CACHE);
            }
            return probeUnderLock();
        } finally {
            probeLock.unlock();
        }
    }

    /**
     * Performs the call, or explains why it was not made. Always invoked holding the lock.
     *
     * <p>A refused permit is not an error here. See the note on the class about why a health
     * check must not fail on this service's own ceiling.
     */
    private Outcome probeUnderLock() {
        if (!rateLimiter.tryAcquire("upstream probe")) {
            Result previous = last.get();
            if (previous != null) {
                log.info("Probe skipped: no outbound permit, answering with the previous result");
                return new Outcome(previous, Source.STALE);
            }
            log.warn("Probe skipped: no outbound permit and nothing has ever been probed");
            return new Outcome(
                    new Result(false, 0, Instant.now(), "not checked: local rate limit", null, null),
                    Source.UNKNOWN);
        }

        Result fresh = probe();
        last.set(fresh);
        return new Outcome(fresh, Source.PROBE);
    }

    private boolean isExpired(Result result) {
        return Duration.between(result.checkedAt(), Instant.now()).compareTo(properties.probeTtl()) >= 0;
    }

    private Result probe() {
        URI target = URI.create(properties.baseUrl() + properties.probePath());
        long startedAt = System.nanoTime();
        try {
            List<DatabaseVersion> versions = restClient.get()
                    .uri(target)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<DatabaseVersion>>() {});

            long latencyMs = millisSince(startedAt);
            DatabaseVersion version = (versions == null || versions.isEmpty()) ? null : versions.get(0);
            String databaseVersion = version == null ? null : version.databaseVersion();
            String databaseUpdatedAt = version == null ? null : version.lastUpdate();

            log.info("Upstream probe succeeded: uri={} latencyMs={} databaseVersion={} lastUpdate={}",
                    target, latencyMs, databaseVersion, databaseUpdatedAt);
            return new Result(true, latencyMs, Instant.now(), "reachable", databaseVersion, databaseUpdatedAt);
        } catch (RuntimeException e) {
            long latencyMs = millisSince(startedAt);
            log.warn("Upstream probe failed: uri={} latencyMs={} reason={}",
                    target, latencyMs, e.getClass().getSimpleName() + ": " + e.getMessage());
            return new Result(false, latencyMs, Instant.now(), e.getClass().getSimpleName(), null, null);
        }
    }

    private static long millisSince(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    /** Where the answer came from, so the caller can report it honestly. */
    public enum Source {
        /** This call went out to the network. */
        PROBE,
        /** The stored result is still within its time to live. */
        CACHE,
        /**
         * The stored result has expired, and either another caller is refreshing it right now or
         * no outbound permit was available. Either way this is the previous answer, not a new one.
         */
        STALE,
        /** No call could be made and nothing has ever been probed, so the state is not known. */
        UNKNOWN
    }

    /**
     * One probe outcome.
     *
     * <p>The database version is recorded because the probe endpoint returns it for free and it
     * is the cheapest way to notice that the upstream data set changed underneath a cache.
     */
    public record Result(
            boolean reachable,
            long latencyMs,
            Instant checkedAt,
            String detail,
            String databaseVersion,
            String databaseUpdatedAt) {

        public long ageSeconds() {
            return Duration.between(checkedAt, Instant.now()).toSeconds();
        }
    }

    /** A result plus where this particular call got it from. */
    public record Outcome(Result result, Source source) {
    }

    /** Shape of one entry in the probe endpoint's response. */
    private record DatabaseVersion(
            @JsonProperty("database_version") String databaseVersion,
            @JsonProperty("last_update") String lastUpdate) {
    }
}
