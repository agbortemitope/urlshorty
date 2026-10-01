package com.urlshorty.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Uniform error body used by every failing request.
 *
 * <p>{@code fieldErrors} only exists for validation failures, so it is annotated to be left out of
 * the JSON when it is null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> fieldErrors) {
}
