package edu.jala.ygocards.observability;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Makes graceful shutdown visible (factor IX).
 *
 * <p>{@code server.shutdown=graceful} is what does the work: on a termination signal the
 * container stops accepting new connections and lets in-flight requests finish within the
 * configured grace period. It prints nothing by itself, so without this component there is no
 * way to show, from the outside, that the process went down in an orderly way rather than
 * being killed. That distinction is the whole point of the factor, so it deserves a line in
 * the log.
 *
 * <p>The wording is deliberately "shutdown signal" rather than "SIGTERM". This hook runs on any
 * orderly context close, which is usually SIGTERM from a container runtime but is also Ctrl+C
 * in a terminal. Claiming it proves a specific signal was received would be more than the code
 * knows.
 */
@Component
public class ShutdownLogger {

    private static final Logger log = LoggerFactory.getLogger(ShutdownLogger.class);

    private final Instant startedAt = Instant.now();
    private final String gracePeriod;

    public ShutdownLogger(@Value("${spring.lifecycle.timeout-per-shutdown-phase}") String gracePeriod) {
        this.gracePeriod = gracePeriod;
    }

    @PreDestroy
    public void onShutdownSignal() {
        long uptimeSeconds = Duration.between(startedAt, Instant.now()).toSeconds();
        log.info("Shutdown signal received after {}s of uptime", uptimeSeconds);
        log.info("Graceful shutdown in progress: no new requests accepted, "
                + "in-flight requests have up to {} to finish", gracePeriod);
        log.info("Nothing needs to be flushed to disk: this process holds no durable state, "
                + "only a bounded cache that is safe to discard");
    }
}
