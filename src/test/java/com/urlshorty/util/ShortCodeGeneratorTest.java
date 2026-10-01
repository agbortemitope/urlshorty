package com.urlshorty.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the code generator. No Spring context is started, so these run in milliseconds.
 */
class ShortCodeGeneratorTest {

    private final ShortCodeGenerator generator = new ShortCodeGenerator();

    @Test
    @DisplayName("generates a code of the documented length")
    void generatesCodeOfExpectedLength() {
        assertThat(generator.generate()).hasSize(ShortCodeGenerator.CODE_LENGTH);
    }

    @Test
    @DisplayName("only uses characters from the Base62 alphabet")
    void usesOnlyBase62Characters() {
        for (int i = 0; i < 1_000; i++) {
            String code = generator.generate();
            for (char character : code.toCharArray()) {
                assertThat(ShortCodeGenerator.ALPHABET).contains(String.valueOf(character));
            }
        }
    }

    @Test
    @DisplayName("does not repeat itself across ten thousand draws")
    void producesDistinctCodes() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            codes.add(generator.generate());
        }

        assertThat(codes).hasSize(10_000);
    }
}
