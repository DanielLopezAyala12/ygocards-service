package edu.jala.ygocards.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounds of the in-process caches (factors IV and VI).
 *
 * <p>The upstream provider asks consumers to store retrieved data locally rather than
 * requesting it repeatedly. Holding it in memory satisfies that without making the process
 * stateful in the sense factor VI forbids: losing this cache costs a cache miss, not
 * correctness, and no request depends on having been served by the same instance before.
 *
 * <p>Both bounds matter. The time to live keeps entries from going stale, and the entry
 * ceiling keeps a long-running process from growing without limit, which is what makes the
 * cache safe to hold in a container with a memory cap.
 *
 * <p>Declared now and used when the card and image endpoints arrive, so that the full set of
 * configuration is visible in one place from the first commit.
 */
@ConfigurationProperties(prefix = "ygo.cache")
public record CacheProperties(
        Duration cardTtl,
        int cardMaxEntries,
        Duration imageTtl,
        int imageMaxEntries) {
}
