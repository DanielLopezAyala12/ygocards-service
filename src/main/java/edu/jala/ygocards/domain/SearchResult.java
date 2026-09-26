package edu.jala.ygocards.domain;

import java.util.List;

/**
 * A page of cards.
 *
 * @param query      what was asked
 * @param count      how many cards this page holds
 * @param totalRows  how many the upstream says match in total, null when it does not report it
 * @param cards      the page itself, empty when nothing matched
 */
public record SearchResult(SearchQuery query, int count, Integer totalRows, List<Card> cards) {

    public static SearchResult empty(SearchQuery query) {
        return new SearchResult(query, 0, 0, List.of());
    }
}
