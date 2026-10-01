package com.urlshorty.dto;

import com.urlshorty.entity.ShortUrl;

import java.time.Instant;

/**
 * Response body for the create, retrieve and update operations.
 *
 * <p>The id is serialised as a JSON string on purpose: the project specification shows
 * {@code "id": "1"}, so clients written against that specification never see a number.
 */
public record UrlResponse(
        String id,
        String url,
        String shortCode,
        Instant createdAt,
        Instant updatedAt) {

    /** Maps a database entity to the API representation. */
    public static UrlResponse from(ShortUrl entity) {
        return new UrlResponse(
                String.valueOf(entity.getId()),
                entity.getOriginalUrl(),
                entity.getShortCode(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
