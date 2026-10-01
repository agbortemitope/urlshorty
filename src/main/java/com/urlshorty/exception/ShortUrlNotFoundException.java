package com.urlshorty.exception;

/**
 * Thrown when a short code does not exist. Translated into an HTTP 404 response by
 * {@link GlobalExceptionHandler}.
 */
public class ShortUrlNotFoundException extends RuntimeException {

    public ShortUrlNotFoundException(String shortCode) {
        super("No short URL found for code '" + shortCode + "'");
    }
}
