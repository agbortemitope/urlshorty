package com.urlshorty.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Implementation of the {@link ValidHttpUrl} constraint.
 *
 * <p>A URL is accepted when it parses as an absolute URI, uses the {@code http} or {@code https}
 * scheme, has a non-blank host and contains no whitespace, which would otherwise break the redirect
 * the frontend performs.
 */
public class HttpUrlValidator implements ConstraintValidator<ValidHttpUrl, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // Missing and blank values are the job of @NotBlank, not of this validator. Returning true
        // here keeps the error response to one precise message per problem.
        if (value == null || value.isBlank()) {
            return true;
        }

        if (containsWhitespace(value)) {
            return false;
        }

        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute()) {
                return false;
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return false;
            }
            return uri.getHost() != null && !uri.getHost().isBlank();
        } catch (URISyntaxException ex) {
            return false;
        }
    }

    private boolean containsWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
