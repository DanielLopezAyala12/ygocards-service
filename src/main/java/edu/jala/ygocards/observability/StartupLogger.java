package edu.jala.ygocards.observability;

import edu.jala.ygocards.admin.AdminRunner;
import edu.jala.ygocards.config.CacheProperties;
import edu.jala.ygocards.config.UpstreamProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Prints the configuration this instance actually resolved, once it is ready to serve.
 *
 * <p>This is evidence for factor III as much as it is an operational convenience. The same
 * image running in two places produces two different blocks here, which is what "configuration
 * lives in the environment" looks like from outside. It also removes the most common support
 * question, which is whether a deployment picked up the variable someone thought they set.
 *
 * <p>Nothing secret is printed because this service holds no secrets: the upstream API needs no
 * key. That is a property of the upstream rather than a virtue of this design, and the report
 * says so. If a credential is ever added, it belongs in the environment and its value must not
 * appear in this block.
 */
@Component
public class StartupLogger {

    private static final Logger log = LoggerFactory.getLogger(StartupLogger.class);

    private final UpstreamProperties upstream;
    private final CacheProperties cache;
    private final String version;
    private final String configuredPort;
    private final String shutdownTimeout;

    public StartupLogger(
            UpstreamProperties upstream,
            CacheProperties cache,
            @Value("${app.version}") String version,
            @Value("${server.port}") String configuredPort,
            @Value("${spring.lifecycle.timeout-per-shutdown-phase}") String shutdownTimeout) {
        this.upstream = upstream;
        this.cache = cache;
        this.version = version;
        this.configuredPort = configuredPort;
        this.shutdownTimeout = shutdownTimeout;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logEffectiveConfiguration(ApplicationReadyEvent event) {
        // An admin run prints the configuration its task actually uses and then exits. Repeating
        // the whole block after the report would bury the result the operator is reading.
        if (event.getArgs() != null && AdminRunner.isAdminInvocation(event.getArgs())) {
            return;
        }
        log.info("Effective configuration for ygocards-service {}", version);
        log.info("  port                 = {}", configuredPort);
        log.info("  upstream.baseUrl     = {}", upstream.baseUrl());
        log.info("  upstream.imageBaseUrl= {}", upstream.imageBaseUrl());
        log.info("  upstream.probePath   = {}", upstream.probePath());
        log.info("  upstream.probeTtl    = {}", upstream.probeTtl());
        log.info("  upstream.rateLimit   = {} requests/second, wait up to {} for a permit",
                upstream.rateLimitPerSecond(), upstream.rateLimitWait());
        log.info("  upstream.connectTimeout = {}", upstream.connectTimeout());
        log.info("  upstream.readTimeout    = {}", upstream.readTimeout());
        log.info("  cache.cardTtl        = {} (max {} entries)", cache.cardTtl(), cache.cardMaxEntries());
        log.info("  cache.imageTtl       = {} (max {} entries)", cache.imageTtl(), cache.imageMaxEntries());
        log.info("  shutdownGracePeriod  = {}", shutdownTimeout);
        log.info("Ready. Liveness at /health, upstream reachability at /health/upstream");
    }
}
