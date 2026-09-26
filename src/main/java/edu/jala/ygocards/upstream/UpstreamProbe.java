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
 * <h2>The probe does not take a rate limiter permit</h2>
 * It is already self limiting: at most one call per time to live, whatever the traffic. Making
 * health checks compete with user requests for permits would mean a busy service starts
 * reporting itself unable to reach an upstream that is answering normally.
 */
@Component
public class UpstreamProbe {

    private static final Logger log = LoggerFactory.getLogger(UpstreamProbe.class);

    private final RestClient restClient;
    private final UpstreamProperties properties;
    private final AtomicReference<Result> last = new AtomicReference<>();
    private final ReentrantLock probeLock = new ReentrantLock();

    public UpstreamProbe(RestClient upstreamRestClient, UpstreamProperties properties) {
        this.restClient = upstreamRestClient;
        this.properties = properties;
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
                Result fresh = probe();
                last.set(fresh);
                return new Outcome(fresh, Source.PROBE);
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
            Result fresh = probe();
            last.set(fresh);
            return new Outcome(fresh, Source.PROBE);
        } finally {
            probeLock.unlock();
        }
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
        /** The stored result has expired and another caller is refreshing it right now. */
        STALE
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
