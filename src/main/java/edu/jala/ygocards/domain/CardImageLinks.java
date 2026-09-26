package edu.jala.ygocards.domain;

/**
 * Where a client should fetch a card image.
 *
 * <p>Both values are paths into this service, never upstream URLs. The provider's usage
 * guidelines forbid hotlinking its image host, so no response this service produces may mention
 * it. Keeping the rewriting here, in the type the API returns, means an endpoint cannot leak the
 * upstream host by forgetting to translate a field.
 */
public record CardImageLinks(String small, String full) {

    public static CardImageLinks forCard(long cardId) {
        return new CardImageLinks(
                "/api/cards/" + cardId + "/image?variant=small",
                "/api/cards/" + cardId + "/image?variant=full");
    }
}
