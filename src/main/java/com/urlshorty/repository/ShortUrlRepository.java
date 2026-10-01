package com.urlshorty.repository;

import com.urlshorty.entity.ShortUrl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link ShortUrl}.
 *
 * <p>Spring generates the implementation at startup from the method names and from the JPQL in the
 * {@link Query} annotation. No hand-written SQL is required.
 */
public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {

    /** Looks a record up by its short code. Empty when the code is unknown. */
    Optional<ShortUrl> findByShortCode(String shortCode);

    /** Cheap existence check used while generating a fresh, unused short code. */
    boolean existsByShortCode(String shortCode);

    /**
     * Increments the access counter inside the database itself.
     *
     * <p>Reading the value into Java, adding one and writing it back loses increments when two
     * requests resolve the same code at the same moment. Letting the database do the arithmetic makes
     * the operation atomic.
     *
     * @return the number of rows updated (0 when the code does not exist)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ShortUrl s set s.accessCount = s.accessCount + 1 where s.shortCode = :shortCode")
    int incrementAccessCount(@Param("shortCode") String shortCode);
}
