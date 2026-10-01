package com.urlshorty.service;

import com.urlshorty.dto.StatsResponse;
import com.urlshorty.dto.UrlResponse;
import com.urlshorty.entity.ShortUrl;
import com.urlshorty.exception.ShortUrlNotFoundException;
import com.urlshorty.repository.ShortUrlRepository;
import com.urlshorty.util.ShortCodeGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the business rules, with the repository replaced by a Mockito double.
 *
 * <p>These cover the situations that are awkward to provoke through the HTTP API, above all what
 * happens when the generator produces a code that is already taken.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UrlShortenerServiceTest {

    @Mock
    private ShortUrlRepository repository;

    @Mock
    private ShortCodeGenerator codeGenerator;

    @InjectMocks
    private UrlShortenerService service;

    @Test
    @DisplayName("create stores the submitted URL under the generated code")
    void createUsesGeneratedCode() {
        when(codeGenerator.generate()).thenReturn("abc1234");
        when(repository.existsByShortCode("abc1234")).thenReturn(false);
        when(repository.save(any(ShortUrl.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UrlResponse response = service.create("https://example.com/page");

        assertThat(response.shortCode()).isEqualTo("abc1234");
        assertThat(response.url()).isEqualTo("https://example.com/page");
        assertThat(response.createdAt()).isNull(); // the entity only gets timestamps on persist
        verify(repository).save(any(ShortUrl.class));
    }

    @Test
    @DisplayName("create retries when the generator produces a code that is already taken")
    void createRetriesOnCollision() {
        when(codeGenerator.generate()).thenReturn("taken01", "taken02", "free003");
        when(repository.existsByShortCode("taken01")).thenReturn(true);
        when(repository.existsByShortCode("taken02")).thenReturn(true);
        when(repository.existsByShortCode("free003")).thenReturn(false);
        when(repository.save(any(ShortUrl.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UrlResponse response = service.create("https://example.com/page");

        assertThat(response.shortCode()).isEqualTo("free003");
        verify(codeGenerator, times(3)).generate();
    }

    @Test
    @DisplayName("create gives up after the maximum number of attempts")
    void createFailsWhenCodeSpaceIsExhausted() {
        when(codeGenerator.generate()).thenReturn("taken01");
        when(repository.existsByShortCode(anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.create("https://example.com/page"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not generate a unique short code");

        verify(codeGenerator, times(UrlShortenerService.MAX_GENERATION_ATTEMPTS)).generate();
        verify(repository, never()).save(any(ShortUrl.class));
    }

    @Test
    @DisplayName("resolve increments the access counter in the database")
    void resolveIncrementsAccessCount() {
        when(repository.findByShortCode("abc1234")).thenReturn(Optional.of(entityWith("abc1234", 4)));

        UrlResponse response = service.resolve("abc1234");

        verify(repository).incrementAccessCount("abc1234");
        assertThat(response.shortCode()).isEqualTo("abc1234");
    }

    @Test
    @DisplayName("resolve reports an unknown code as not found")
    void resolveThrowsWhenMissing() {
        when(repository.findByShortCode("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve("missing"))
                .isInstanceOf(ShortUrlNotFoundException.class)
                .hasMessageContaining("missing");

        verify(repository, never()).incrementAccessCount(anyString());
    }

    @Test
    @DisplayName("stats returns the stored access count without changing it")
    void statsReturnsAccessCount() {
        when(repository.findByShortCode("abc1234")).thenReturn(Optional.of(entityWith("abc1234", 12)));

        StatsResponse response = service.stats("abc1234");

        assertThat(response.accessCount()).isEqualTo(12);
        verify(repository, never()).incrementAccessCount(anyString());
    }

    private ShortUrl entityWith(String shortCode, long accessCount) {
        ShortUrl entity = new ShortUrl("https://example.com/page", shortCode);
        entity.setAccessCount(accessCount);
        return entity;
    }
}
