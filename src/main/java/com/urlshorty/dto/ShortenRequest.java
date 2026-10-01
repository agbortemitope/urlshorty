package com.urlshorty.dto;

import com.urlshorty.validation.ValidHttpUrl;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body of {@code POST /shorten} and {@code PUT /shorten/{shortCode}}.
 *
 * <p>The three annotations are the whole input contract: the field must be present, must not exceed
 * the 2048 characters the database column allows, and must be a real http(s) URL.
 */
public record ShortenRequest(
        @NotBlank(message = "url must not be missing or empty")
        @Size(max = 2048, message = "url must not exceed 2048 characters")
        @ValidHttpUrl
        String url) {
}
