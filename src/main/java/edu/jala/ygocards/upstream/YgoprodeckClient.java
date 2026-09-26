package edu.jala.ygocards.upstream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import edu.jala.ygocards.config.UpstreamProperties;
import edu.jala.ygocards.domain.Card;
import edu.jala.ygocards.domain.CardImageLinks;
import edu.jala.ygocards.domain.SearchQuery;
import edu.jala.ygocards.domain.SearchResult;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The only place in this service that talks to the upstream API.
 *
 * <p>Everything that belongs to the provider contract lives here: the parameter names, the
 * decision that a 400 means "nothing matched" rather than "you made a mistake", the rate limit,
 * and the mapping from the provider's JSON into this project's own types. A second place calling
 * the upstream directly would be a second place to get all of that wrong.
 *
 * <p>Image URLs are rebuilt from {@code YGO_IMAGE_BASE_URL} rather than taken from whatever the
 * search response happened to contain. That is not a style preference: an image endpoint that
 * depended on remembering the search result would depend on the cache, and a cold instance asked
 * for an image it never searched for would have nothing to serve.
 */
@Component
public class YgoprodeckClient {

    private static final Logger log = LoggerFactory.getLogger(YgoprodeckClient.class);

    private final RestClient restClient;
    private final UpstreamProperties properties;
    private final UpstreamRateLimiter rateLimiter;

    public YgoprodeckClient(
            RestClient upstreamRestClient,
            UpstreamProperties properties,
            UpstreamRateLimiter rateLimiter) {
        this.restClient = upstreamRestClient;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
    }

    /** Fuzzy search by name. Returns an empty page when nothing matches. */
    public SearchResult search(SearchQuery query) {
        acquirePermitOrFail(rateLimiter.forSearch(), "card search");

        URI target = URI.create(properties.baseUrl()
                + "/cardinfo.php?fname=" + URLEncoder.encode(query.name(), StandardCharsets.UTF_8)
                + "&num=" + query.limit()
                + "&offset=" + query.offset());

        long startedAt = System.nanoTime();
        try {
            UpstreamSearchResponse response = restClient.get()
                    .uri(target)
                    .retrieve()
                    .body(UpstreamSearchResponse.class);

            long latencyMs = millisSince(startedAt);
            List<Card> cards = (response == null || response.data() == null)
                    ? List.of()
                    : response.data().stream().map(YgoprodeckClient::toCard).toList();
            Integer totalRows = (response == null || response.meta() == null)
                    ? null
                    : response.meta().totalRows();

            log.info("Upstream search: name='{}' limit={} offset={} latencyMs={} returned={} totalRows={}",
                    query.name(), query.limit(), query.offset(), latencyMs, cards.size(), totalRows);
            return new SearchResult(query, cards.size(), totalRows, cards);

        } catch (HttpClientErrorException.BadRequest e) {
            // The provider answers 400 with an "error" message when a query matches no card.
            // That is an empty result, not a client mistake, so it is not propagated as one.
            long latencyMs = millisSince(startedAt);
            log.info("Upstream search: name='{}' latencyMs={} returned=0 (upstream reported no match)",
                    query.name(), latencyMs);
            return SearchResult.empty(query);

        } catch (ResourceAccessException e) {
            throw timeout("card search", target, startedAt, e);
        } catch (RestClientException e) {
            throw unavailable("card search", target, startedAt, e);
        }
    }

    /** Fetches one card image from the upstream host. */
    public CardImage fetchImage(long cardId, ImageVariant variant) {
        acquirePermitOrFail(rateLimiter.forImage(), "card image");

        URI target = URI.create(variant == ImageVariant.SMALL
                ? properties.smallImageUrl(cardId)
                : properties.fullImageUrl(cardId));

        long startedAt = System.nanoTime();
        try {
            ResponseEntity<byte[]> response = restClient.get()
                    .uri(target)
                    .retrieve()
                    .toEntity(byte[].class);

            long latencyMs = millisSince(startedAt);
            byte[] body = response.getBody() == null ? new byte[0] : response.getBody();
            MediaType contentType = response.getHeaders().getContentType();
            String mediaType = contentType == null ? MediaType.IMAGE_JPEG_VALUE : contentType.toString();

            log.info("Upstream image: cardId={} variant={} latencyMs={} bytes={} contentType={}",
                    cardId, variant, latencyMs, body.length, mediaType);
            return new CardImage(body, mediaType);

        } catch (HttpClientErrorException.NotFound e) {
            long latencyMs = millisSince(startedAt);
            log.info("Upstream image: cardId={} variant={} latencyMs={} not found upstream",
                    cardId, variant, latencyMs);
            return null;
        } catch (ResourceAccessException e) {
            throw timeout("card image", target, startedAt, e);
        } catch (RestClientException e) {
            throw unavailable("card image", target, startedAt, e);
        }
    }

    private void acquirePermitOrFail(TokenBucket.Outcome outcome, String what) {
        if (!outcome.granted()) {
            throw new RateLimitedException(
                    "Outbound rate limit reached for " + what, outcome.retryAfterSeconds());
        }
    }

    private UpstreamTimeoutException timeout(String what, URI target, long startedAt, Exception cause) {
        long latencyMs = millisSince(startedAt);
        log.warn("Upstream {} timed out: uri={} latencyMs={} reason={}",
                what, target, latencyMs, cause.getMessage());
        return new UpstreamTimeoutException("Upstream did not answer in time for " + what, cause);
    }

    private UpstreamUnavailableException unavailable(String what, URI target, long startedAt, Exception cause) {
        long latencyMs = millisSince(startedAt);
        log.warn("Upstream {} failed: uri={} latencyMs={} reason={}: {}",
                what, target, latencyMs, cause.getClass().getSimpleName(), cause.getMessage());
        return new UpstreamUnavailableException("Upstream call failed for " + what, cause);
    }

    private static Card toCard(UpstreamCard upstream) {
        return new Card(
                upstream.id(),
                upstream.name(),
                upstream.type(),
                upstream.frameType(),
                upstream.race(),
                upstream.attribute(),
                upstream.atk(),
                upstream.def(),
                upstream.level(),
                upstream.archetype(),
                upstream.desc(),
                CardImageLinks.forCard(upstream.id()));
    }

    private static long millisSince(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    /** Which artwork size to serve. */
    public enum ImageVariant {
        SMALL,
        FULL;

        public static ImageVariant from(String raw) {
            return "small".equalsIgnoreCase(raw) ? SMALL : FULL;
        }
    }

    /** Image bytes with the media type the upstream reported. */
    public record CardImage(byte[] bytes, String contentType) {
    }

    // --- Upstream wire shapes, kept private so they cannot leak into the public API ----------

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UpstreamSearchResponse(List<UpstreamCard> data, UpstreamMeta meta) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UpstreamMeta(@JsonProperty("total_rows") Integer totalRows) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UpstreamCard(
            long id,
            String name,
            String type,
            String frameType,
            String desc,
            String race,
            String attribute,
            Integer atk,
            Integer def,
            Integer level,
            String archetype) {
    }
}
