package edu.jala.ygocards.web;

import edu.jala.ygocards.application.CardImageService;
import edu.jala.ygocards.application.CardSearchService;
import edu.jala.ygocards.domain.SearchQuery;
import edu.jala.ygocards.domain.SearchResult;
import edu.jala.ygocards.upstream.YgoprodeckClient.CardImage;
import edu.jala.ygocards.upstream.YgoprodeckClient.ImageVariant;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The public card API. */
@RestController
@RequestMapping("/api/cards")
public class CardController {

    private static final int MAX_LIMIT = 100;

    private final CardSearchService searchService;
    private final CardImageService imageService;

    public CardController(CardSearchService searchService, CardImageService imageService) {
        this.searchService = searchService;
        this.imageService = imageService;
    }

    /**
     * Fuzzy search by name.
     *
     * <p>A query that matches nothing returns 200 with an empty list, not 404 and not the 400 the
     * upstream answers with. "I looked and found nothing" is a successful search, and making the
     * caller handle an error status for it would push the provider's contract onto every client.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam String name,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        if (name == null || name.isBlank()) {
            throw new InvalidRequestException("name must not be blank");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidRequestException("limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw new InvalidRequestException("offset must not be negative");
        }

        CardSearchService.Outcome outcome =
                searchService.search(new SearchQuery(name.trim(), limit, offset));
        SearchResult result = outcome.result();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", result.query());
        body.put("count", result.count());
        body.put("totalRows", result.totalRows());
        body.put("cache", outcome.cacheHit() ? "hit" : "miss");
        body.put("cards", result.cards());

        return ResponseEntity.ok()
                .header("X-Cache", outcome.cacheHit() ? "HIT" : "MISS")
                .body(body);
    }

    /**
     * Serves a card image from this service.
     *
     * <p>The upstream URL is rebuilt from configuration using the card id, so this endpoint works
     * on an instance that never served the corresponding search. Depending on a remembered URL
     * would make the image endpoint depend on the cache, and a cold instance would fail.
     */
    @GetMapping("/{cardId}/image")
    public ResponseEntity<byte[]> image(
            @PathVariable long cardId,
            @RequestParam(defaultValue = "full") String variant) {

        CardImageService.Outcome outcome = imageService.image(cardId, ImageVariant.from(variant));
        CardImage image = outcome.image();

        if (image == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .header("X-Cache", outcome.cacheHit() ? "HIT" : "MISS")
                .body(image.bytes());
    }
}
