package com.urlshorty.dto;

import com.urlshorty.entity.ShortUrl;

import java.time.Instant;

/**
 * Response body of {@code GET /shorten/{shortCode}/stats}.
 *
 * <p>Everything a normal lookup returns, plus the number of times the short code was resolved.
 */
public record StatsResponse(
        String id,
        String url,
        String shortCode,
        Instant createdAt,
        Instant updatedAt,
        long accessCount) {

    /** Maps a database entity to the API representation. */
    public static StatsResponse from(ShortUrl entity) {
        return new StatsResponse(
                String.valueOf(entity.getId()),
                entity.getOriginalUrl(),
                entity.getShortCode(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getAccessCount());
    }
}
