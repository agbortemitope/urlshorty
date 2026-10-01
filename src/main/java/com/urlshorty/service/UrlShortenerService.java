package com.urlshorty.service;

import com.urlshorty.dto.StatsResponse;
import com.urlshorty.dto.UrlResponse;
import com.urlshorty.entity.ShortUrl;
import com.urlshorty.exception.ShortUrlNotFoundException;
import com.urlshorty.repository.ShortUrlRepository;
import com.urlshorty.util.ShortCodeGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic of the service: everything that happens between the controller and the database.
 *
 * <p>Keeping this out of the controller means the rules can be read in one place and tested without
 * starting a web server.
 */
@Service
public class UrlShortenerService {

    /** How many codes are generated before the service gives up. Reaching this is practically impossible. */
    public static final int MAX_GENERATION_ATTEMPTS = 10;

    private final ShortUrlRepository repository;
    private final ShortCodeGenerator codeGenerator;

    public UrlShortenerService(ShortUrlRepository repository, ShortCodeGenerator codeGenerator) {
        this.repository = repository;
        this.codeGenerator = codeGenerator;
    }

    /** Creates a new short URL and returns the stored record. */
    @Transactional
    public UrlResponse create(String originalUrl) {
        String shortCode = generateUniqueCode();
        ShortUrl saved = repository.save(new ShortUrl(originalUrl, shortCode));
        return UrlResponse.from(saved);
    }

    /**
     * Resolves a short code, counts the access and returns the record.
     *
     * <p>The counter is incremented with a single UPDATE statement rather than by reading the value
     * into Java and writing it back, so simultaneous lookups cannot overwrite each other's
     * increments.
     */
    @Transactional
    public UrlResponse resolve(String shortCode) {
        ShortUrl entity = findOrThrow(shortCode);

        // The database performs the real increment. The line after it only makes the value in this
        // response match what was just stored: the repository call cleared the persistence context,
        // so the entity is detached and this change is never written back on its own.
        repository.incrementAccessCount(shortCode);
        entity.setAccessCount(entity.getAccessCount() + 1);

        return UrlResponse.from(entity);
    }

    /**
     * Points an existing short code at a different long URL.
     *
     * <p>{@code saveAndFlush} is used on purpose. Hibernate only refreshes {@code updatedAt} in its
     * {@code @PreUpdate} callback when the UPDATE statement is written, which normally happens at
     * commit time - after this method has already built its response. Flushing first means the
     * response carries the new timestamp instead of the previous one.
     */
    @Transactional
    public UrlResponse update(String shortCode, String newOriginalUrl) {
        ShortUrl entity = findOrThrow(shortCode);
        entity.setOriginalUrl(newOriginalUrl);
        ShortUrl saved = repository.saveAndFlush(entity);
        return UrlResponse.from(saved);
    }

    /** Removes a short code. */
    @Transactional
    public void delete(String shortCode) {
        ShortUrl entity = findOrThrow(shortCode);
        repository.delete(entity);
    }

    /** Returns the record together with its access statistics. */
    @Transactional(readOnly = true)
    public StatsResponse stats(String shortCode) {
        return StatsResponse.from(findOrThrow(shortCode));
    }

    /**
     * Generates codes until one is free.
     *
     * <p>The loop is a convenience, not a guarantee: two requests can still pick the same code in the
     * same millisecond. The unique constraint on the column is what actually protects the data; the
     * loop only makes a clash rare enough to be uninteresting.
     */
    private String generateUniqueCode() {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String candidate = codeGenerator.generate();
            if (!repository.existsByShortCode(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Could not generate a unique short code after " + MAX_GENERATION_ATTEMPTS + " attempts");
    }

    private ShortUrl findOrThrow(String shortCode) {
        return repository.findByShortCode(shortCode)
                .orElseThrow(() -> new ShortUrlNotFoundException(shortCode));
    }
}
