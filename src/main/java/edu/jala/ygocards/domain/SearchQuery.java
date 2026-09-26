package edu.jala.ygocards.domain;

/** The query a search result answers, echoed back so a cached response stays self describing. */
public record SearchQuery(String name, int limit, int offset) {
}
