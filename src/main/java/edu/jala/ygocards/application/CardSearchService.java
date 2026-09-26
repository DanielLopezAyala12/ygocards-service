package edu.jala.ygocards.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import edu.jala.ygocards.config.CacheProperties;
import edu.jala.ygocards.domain.SearchQuery;
import edu.jala.ygocards.domain.SearchResult;
import edu.jala.ygocards.upstream.YgoprodeckClient;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Card search, with the local copy of upstream data the provider asks consumers to keep.
 *
 * <p>The provider's guidelines ask callers to store retrieved data rather than request it
 * repeatedly, and its rate limit makes that more than politeness. Holding the answers in memory
 * satisfies both without making the process stateful in the sense factor VI forbids: losing this
 * cache costs a cache miss, not correctness, and no request depends on having been served by the
 * same instance before.
 *
 * <p>Both bounds are configuration. The time to live keeps entries from going stale against a
 * data set that changes, and the entry ceiling keeps a long-running process from growing without
 * limit, which is what makes the cache safe inside a container with a memory cap.
 */
@Service
public class CardSearchService {

    private static final Logger log = LoggerFactory.getLogger(CardSearchService.class);

    private final YgoprodeckClient client;
    private final Cache<String, SearchResult> cache;

    public CardSearchService(YgoprodeckClient client, CacheProperties cacheProperties) {
        this.client = client;
        this.cache = Caffeine.newBuilder()
                .maximumSize(cacheProperties.cardMaxEntries())
                .expireAfterWrite(cacheProperties.cardTtl())
                .recordStats()
                .build();
    }

    public Outcome search(SearchQuery query) {
        String key = cacheKey(query);

        SearchResult hit = cache.getIfPresent(key);
        if (hit != null) {
            log.info("Card cache HIT  key='{}' entries={}", key, cache.estimatedSize());
            return new Outcome(hit, true);
        }

        log.info("Card cache MISS key='{}' entries={}, calling upstream", key, cache.estimatedSize());
        SearchResult fresh = client.search(query);
        cache.put(key, fresh);
        return new Outcome(fresh, false);
    }

    /**
     * The key is the whole query, normalised. Two searches that differ only in capitalisation are
     * the same request upstream, and caching them separately would double the calls for nothing.
     */
    private static String cacheKey(SearchQuery query) {
        return query.name().trim().toLowerCase(Locale.ROOT)
                + "|" + query.limit()
                + "|" + query.offset();
    }

    /** A result plus whether it was already held locally. */
    public record Outcome(SearchResult result, boolean cacheHit) {
    }
}
