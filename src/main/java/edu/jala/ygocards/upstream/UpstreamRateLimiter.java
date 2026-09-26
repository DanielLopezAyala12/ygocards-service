package edu.jala.ygocards.upstream;

import edu.jala.ygocards.config.UpstreamProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Keeps outbound traffic under the ceiling the upstream provider publishes, with a separate
 * budget for each class of traffic.
 *
 * <h2>Why two budgets and not one</h2>
 * A search and an image are not the same kind of call, and a single counter treats them as if
 * they were. One search produces one outbound call, and the page it returns then asks for one
 * image per card, so the two classes stand in a ratio of roughly one to twenty four. Sharing a
 * counter between them guarantees that the abundant class crowds out the scarce one: measured
 * against a single ceiling of eight per second, every refusal in the log was an image and none
 * was a search, and that was luck rather than design. With a different arrival order the user
 * would have lost the search they asked for so that images could load.
 *
 * <p>The two classes also deserve different patience, for a reason that has nothing to do with
 * volume. A search was requested by a person who is waiting for it, so it needs a small budget
 * that is reliably there, and a short wait, because a spinner that hangs is worse than an honest
 * failure. An image is derived from that search, the user is not watching any particular one of
 * them, they are idempotent and cacheable for an hour, and a missing one degrades a single card
 * out of twenty four. So an image can afford to wait much longer than a search.
 *
 * <h2>Known limitation</h2>
 * Both budgets count inside one process. Two replicas configured at three searches and nine
 * images per second would together send twenty four, which is over the provider's ceiling. There
 * are now two limits that are not shared between replicas rather than one, so scaling out still
 * needs an external coordinator. This is recorded as a deferred improvement rather than claimed
 * as solved.
 */
@Component
public class UpstreamRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(UpstreamRateLimiter.class);

    private final TokenBucket searchBucket;
    private final TokenBucket imageBucket;
    private final Duration searchWait;
    private final Duration imageWait;
    private final int searchPerSecond;
    private final int imagePerSecond;

    public UpstreamRateLimiter(UpstreamProperties properties) {
        this.searchPerSecond = properties.rateLimitSearchPerSecond();
        this.imagePerSecond = properties.rateLimitImagePerSecond();
        this.searchWait = properties.rateLimitSearchWait();
        this.imageWait = properties.rateLimitImageWait();
        this.searchBucket = new TokenBucket(searchPerSecond);
        this.imageBucket = new TokenBucket(imagePerSecond);
    }

    /** A card search, which a person is waiting for. */
    public TokenBucket.Outcome forSearch() {
        return take(searchBucket, searchWait, "card search", searchPerSecond);
    }

    /** A card image, derived from a search and tolerant of a longer wait. */
    public TokenBucket.Outcome forImage() {
        return take(imageBucket, imageWait, "card image", imagePerSecond);
    }

    /**
     * The health probe, which draws on the search budget.
     *
     * <p>It belongs with searches rather than with images because it is low volume and low
     * latency by nature, and giving it a third budget would be precision without a purpose: at
     * one call per probe time to live it cannot exhaust anything. A refused probe is never an
     * error, which {@link UpstreamProbe} handles.
     */
    public TokenBucket.Outcome forProbe() {
        return take(searchBucket, searchWait, "upstream probe", searchPerSecond);
    }

    private TokenBucket.Outcome take(TokenBucket bucket, Duration maxWait, String what, int rate) {
        TokenBucket.Outcome outcome = bucket.acquire(maxWait);

        if (!outcome.granted()) {
            log.warn("Rate limiter denied {}: would have waited {}ms against a budget of {}ms, "
                            + "{} requests/second ceiling for this class",
                    what, outcome.waitMillis(), maxWait.toMillis(), rate);
        } else if (outcome.waitNanos() > 0) {
            log.info("Rate limiter delayed {} by {}ms to stay under {} requests/second",
                    what, outcome.waitMillis(), rate);
        } else {
            log.debug("Rate limiter granted {} immediately", what);
        }
        return outcome;
    }
}
