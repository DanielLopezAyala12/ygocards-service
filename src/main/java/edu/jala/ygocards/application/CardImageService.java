package edu.jala.ygocards.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import edu.jala.ygocards.config.CacheProperties;
import edu.jala.ygocards.upstream.YgoprodeckClient;
import edu.jala.ygocards.upstream.YgoprodeckClient.CardImage;
import edu.jala.ygocards.upstream.YgoprodeckClient.ImageVariant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Serves card images through this service instead of letting a browser reach the upstream host.
 *
 * <p>The provider prohibits hotlinking its image host and asks consumers to re-host what they
 * use. Writing the files to local disk would satisfy the provider and break factor VI, because a
 * process filesystem is ephemeral and must not be treated as durable storage. Holding a bounded
 * number of images in memory satisfies both: nothing is written, and nothing that is lost on a
 * restart was load bearing.
 *
 * <p>Durable image storage belongs in an attached resource such as object storage, reached
 * through a URL in the environment. That is the correct answer and it is deliberately out of
 * scope here, recorded as a deferred factor rather than quietly skipped.
 *
 * <p>The ceiling is counted in entries rather than bytes, which is worth stating because it makes
 * the memory bound approximate: two hundred full size images at roughly 150 KB each is about
 * 30 MB. A byte-aware weigher would be the stricter choice if the limit ever mattered.
 */
@Service
public class CardImageService {

    private static final Logger log = LoggerFactory.getLogger(CardImageService.class);

    private final YgoprodeckClient client;
    private final Cache<String, CardImage> cache;

    public CardImageService(YgoprodeckClient client, CacheProperties cacheProperties) {
        this.client = client;
        this.cache = Caffeine.newBuilder()
                .maximumSize(cacheProperties.imageMaxEntries())
                .expireAfterWrite(cacheProperties.imageTtl())
                .recordStats()
                .build();
    }

    /** Returns the image, or an outcome holding null when the upstream has none for this card. */
    public Outcome image(long cardId, ImageVariant variant) {
        String key = cardId + "|" + variant;

        CardImage hit = cache.getIfPresent(key);
        if (hit != null) {
            log.info("Image cache HIT  key='{}' bytes={} entries={}",
                    key, hit.bytes().length, cache.estimatedSize());
            return new Outcome(hit, true);
        }

        log.info("Image cache MISS key='{}' entries={}, calling upstream", key, cache.estimatedSize());
        CardImage fetched = client.fetchImage(cardId, variant);
        if (fetched != null) {
            cache.put(key, fetched);
        }
        return new Outcome(fetched, false);
    }

    /** An image plus whether it was already held locally. Image is null when none exists. */
    public record Outcome(CardImage image, boolean cacheHit) {
    }
}
