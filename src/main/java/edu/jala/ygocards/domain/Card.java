package edu.jala.ygocards.domain;

/**
 * One card, as this API presents it.
 *
 * <p>Deliberately not the upstream shape. Passing the provider's JSON straight through would tie
 * this API's contract to theirs, and would carry the upstream image host into responses that must
 * not contain it. Mapping into this record is where both problems are solved at once.
 */
public record Card(
        long id,
        String name,
        String type,
        String frameType,
        String race,
        String attribute,
        Integer atk,
        Integer def,
        Integer level,
        String archetype,
        String description,
        CardImageLinks image) {
}
