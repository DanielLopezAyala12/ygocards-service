package edu.jala.ygocards.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The upstream card API, attached as a backing service (factor IV).
 *
 * <p>Nothing here is hard coded. The base URL in particular is a deployment decision:
 * pointing the service at a mirror, a proxy or a local stub is a change of environment
 * variable, not a change of code.
 *
 * @param baseUrl         root of the card API, from {@code YGO_API_BASE_URL}
 * @param imageBaseUrl    root of the card image host, from {@code YGO_IMAGE_BASE_URL}. The
 *                        browser never sees this value: image URLs in our responses point at
 *                        this service, and this service rebuilds the upstream URL from this
 *                        setting on each request. Rebuilding rather than remembering the URL
 *                        the search returned is what keeps the image endpoint independent of
 *                        the cache, so a cold instance can still serve an image.
 * @param probePath       cheap request used to decide whether the upstream is reachable. The
 *                        default is the endpoint the provider publishes for exactly this
 *                        purpose, which answers in tens of bytes rather than returning card
 *                        data nobody asked for.
 * @param probeTtl        how long a probe result stays usable before another call is made.
 *                        This exists so that polling the health endpoint cannot consume the
 *                        upstream request budget it is meant to protect.
 * @param rateLimitPerSecond outbound request ceiling. The upstream contract allows 20 per
 *                        second and blocks the caller for an hour beyond that, so the default
 *                        sits well under the limit: the margin is cheap and the penalty is not.
 * @param rateLimitWait   how long a request may wait for a permit before the service gives up
 *                        and answers 503. Queueing without a bound would turn a burst into
 *                        exhausted request threads, which is an outage rather than a delay.
 * @param connectTimeout  TCP connect timeout for upstream calls
 * @param readTimeout     response read timeout for upstream calls
 */
@ConfigurationProperties(prefix = "ygo.upstream")
public record UpstreamProperties(
        String baseUrl,
        String imageBaseUrl,
        String probePath,
        Duration probeTtl,
        int rateLimitPerSecond,
        Duration rateLimitWait,
        Duration connectTimeout,
        Duration readTimeout) {

    /** Upstream URL for the full size image of a card. */
    public String fullImageUrl(long cardId) {
        return baseUrl(imageBaseUrl) + "/cards/" + cardId + ".jpg";
    }

    /** Upstream URL for the small image of a card. */
    public String smallImageUrl(long cardId) {
        return baseUrl(imageBaseUrl) + "/cards_small/" + cardId + ".jpg";
    }

    private static String baseUrl(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
