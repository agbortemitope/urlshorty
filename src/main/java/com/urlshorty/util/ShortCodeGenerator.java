package com.urlshorty.util;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Generates random Base62 short codes.
 *
 * <p>Base62 uses the characters {@code 0-9}, {@code A-Z} and {@code a-z}, all of which are safe in a
 * URL path without percent-encoding. With the default length of 7 characters the number of possible
 * codes is 62^7 = 3,521,614,606,208, roughly 3.5 trillion.
 *
 * <p>{@link SecureRandom} is used instead of {@link java.util.Random} because it is
 * cryptographically strong: an attacker cannot predict the next code from the previous ones. That
 * matters here, because guessing a code is equivalent to discovering somebody else's URL.
 */
@Component
public class ShortCodeGenerator {

    /** Characters available to every position of the code. */
    public static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    /** Number of characters in a generated code. */
    public static final int CODE_LENGTH = 7;

    private final SecureRandom random = new SecureRandom();

    /**
     * Produces one random code. Uniqueness is not checked here; the service layer retries when the
     * generated code is already taken.
     */
    public String generate() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
