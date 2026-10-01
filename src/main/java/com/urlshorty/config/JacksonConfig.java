package com.urlshorty.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Makes every {@link Instant} in a response look the same.
 *
 * <p>Jackson's default Instant output omits the fractional part when it happens to be zero, so the
 * same field would sometimes read {@code 2026-10-01T10:53:42Z} and sometimes
 * {@code 2026-10-01T10:53:42.123Z}. Fixing the pattern removes that uncertainty: clients can parse
 * every timestamp with one format, and the milliseconds are always there.
 */
@Configuration
public class JacksonConfig {

    /** ISO-8601 / RFC 3339 in UTC with millisecond precision, for example 2026-10-01T10:53:42.123Z. */
    private static final DateTimeFormatter ISO_MILLIS_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer instantSerializerCustomizer() {
        return builder -> builder.serializerByType(Instant.class, new IsoInstantSerializer());
    }

    /** Writes an {@link Instant} with the fixed pattern above, always in UTC. */
    static class IsoInstantSerializer extends JsonSerializer<Instant> {

        @Override
        public void serialize(Instant value, JsonGenerator generator, SerializerProvider provider)
                throws IOException {
            generator.writeString(ISO_MILLIS_UTC.format(value));
        }
    }
}
