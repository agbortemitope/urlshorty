package com.urlshorty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshorty.repository.ShortUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests of the REST API.
 *
 * <p>The application is started for real (with an in-memory database) and requests are pushed
 * through the whole stack: HTTP layer, validation, service, repository and back. These are the tests
 * that prove the behaviour described in the README.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ShortenApiIntegrationTest {

    private static final String VALID_URL = "https://www.example.com/some/long/url";
    private static final String UPDATED_URL = "https://www.example.com/some/updated/url";
    private static final String ISO_INSTANT_MILLIS =
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ShortUrlRepository repository;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    // ------------------------------------------------------------------ create

    @Test
    @DisplayName("POST /shorten returns 201 with the new record and a Location header")
    void createReturnsCreatedRecord() throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("url", VALID_URL)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/shorten/")))
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.url").value(VALID_URL))
                .andExpect(jsonPath("$.shortCode").isString())
                .andExpect(jsonPath("$.shortCode").value(matchesPattern("[0-9A-Za-z]{7}")))
                .andExpect(jsonPath("$.createdAt").value(matchesPattern(ISO_INSTANT_MILLIS)))
                .andExpect(jsonPath("$.updatedAt").value(matchesPattern(ISO_INSTANT_MILLIS)));
    }

    @Test
    @DisplayName("POST /shorten stores exactly one row")
    void createPersistsOneRow() throws Exception {
        createShortUrl(VALID_URL);

        org.assertj.core.api.Assertions.assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("POST /shorten rejects a URL that is not http(s)")
    void createRejectsNonHttpUrl() throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("url", "ftp://example.com/file")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.url").exists());
    }

    @Test
    @DisplayName("POST /shorten rejects a relative path instead of an absolute URL")
    void createRejectsRelativeUrl() throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("url", "/some/path")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.url").exists());
    }

    @Test
    @DisplayName("POST /shorten rejects a blank url")
    void createRejectsBlankUrl() throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("url", "   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.url").value("url must not be missing or empty"));
    }

    @Test
    @DisplayName("POST /shorten rejects a body without the url property")
    void createRejectsMissingUrl() throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.url").exists());
    }

    @Test
    @DisplayName("POST /shorten rejects a URL longer than the column allows")
    void createRejectsOverlongUrl() throws Exception {
        String tooLong = "https://example.com/" + "a".repeat(2100);

        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("url", tooLong)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.url").value("url must not exceed 2048 characters"));
    }

    @Test
    @DisplayName("POST /shorten rejects a body that is not valid JSON")
    void createRejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("not valid JSON")));
    }

    // ---------------------------------------------------------------- retrieve

    @Test
    @DisplayName("GET /shorten/{code} returns the original URL")
    void retrieveReturnsOriginalUrl() throws Exception {
        String shortCode = createShortUrl(VALID_URL);

        mockMvc.perform(get("/shorten/{shortCode}", shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(VALID_URL))
                .andExpect(jsonPath("$.shortCode").value(shortCode));
    }

    @Test
    @DisplayName("every lookup is counted, and /stats reports the total")
    void retrieveIncrementsAccessCount() throws Exception {
        String shortCode = createShortUrl(VALID_URL);

        mockMvc.perform(get("/shorten/{shortCode}", shortCode)).andExpect(status().isOk());
        mockMvc.perform(get("/shorten/{shortCode}", shortCode)).andExpect(status().isOk());
        mockMvc.perform(get("/shorten/{shortCode}", shortCode)).andExpect(status().isOk());

        mockMvc.perform(get("/shorten/{shortCode}/stats", shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessCount").value(3))
                .andExpect(jsonPath("$.url").value(VALID_URL));
    }

    @Test
    @DisplayName("a brand new short URL has an access count of zero")
    void statsStartsAtZero() throws Exception {
        String shortCode = createShortUrl(VALID_URL);

        mockMvc.perform(get("/shorten/{shortCode}/stats", shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessCount").value(0));
    }

    @Test
    @DisplayName("GET /shorten/{code} returns 404 for an unknown code")
    void retrieveUnknownCodeReturnsNotFound() throws Exception {
        mockMvc.perform(get("/shorten/{shortCode}", "doesnotexist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value(containsString("doesnotexist")));
    }

    @Test
    @DisplayName("GET /shorten/{code}/stats returns 404 for an unknown code")
    void statsUnknownCodeReturnsNotFound() throws Exception {
        mockMvc.perform(get("/shorten/{shortCode}/stats", "doesnotexist"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ update

    @Test
    @DisplayName("PUT /shorten/{code} repoints the code at a new URL")
    void updateChangesTheUrl() throws Exception {
        JsonNode created = createShortUrlRecord(VALID_URL);
        String shortCode = created.get("shortCode").asText();
        String createdAt = created.get("createdAt").asText();

        // A few milliseconds pass so that the new timestamp is distinguishable at millisecond
        // precision. Without the flush inside the service, the response below would still carry the
        // old updatedAt value.
        Thread.sleep(5);

        MvcResult updateResult = mockMvc.perform(put("/shorten/{shortCode}", shortCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("url", UPDATED_URL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(UPDATED_URL))
                .andExpect(jsonPath("$.shortCode").value(shortCode))
                .andExpect(jsonPath("$.createdAt").value(createdAt))
                .andExpect(jsonPath("$.updatedAt").value(not(createdAt)))
                .andReturn();

        String updatedAt = objectMapper.readTree(updateResult.getResponse().getContentAsString())
                .get("updatedAt").asText();

        // The change must survive a round trip through the database, and the timestamp reported by
        // the update must be the one that was actually stored.
        mockMvc.perform(get("/shorten/{shortCode}", shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(UPDATED_URL))
                .andExpect(jsonPath("$.createdAt").value(createdAt))
                .andExpect(jsonPath("$.updatedAt").value(updatedAt));
    }

    @Test
    @DisplayName("PUT /shorten/{code} rejects an invalid URL")
    void updateRejectsInvalidUrl() throws Exception {
        String shortCode = createShortUrl(VALID_URL);

        mockMvc.perform(put("/shorten/{shortCode}", shortCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("url", "not a url")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.url").exists());
    }

    @Test
    @DisplayName("PUT /shorten/{code} returns 404 for an unknown code")
    void updateUnknownCodeReturnsNotFound() throws Exception {
        mockMvc.perform(put("/shorten/{shortCode}", "doesnotexist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("url", UPDATED_URL)))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ delete

    @Test
    @DisplayName("DELETE /shorten/{code} returns 204 and removes the record")
    void deleteRemovesTheRecord() throws Exception {
        String shortCode = createShortUrl(VALID_URL);

        mockMvc.perform(delete("/shorten/{shortCode}", shortCode))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/shorten/{shortCode}", shortCode))
                .andExpect(status().isNotFound());

        org.assertj.core.api.Assertions.assertThat(repository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("DELETE /shorten/{code} returns 404 for an unknown code")
    void deleteUnknownCodeReturnsNotFound() throws Exception {
        mockMvc.perform(delete("/shorten/{shortCode}", "doesnotexist"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------- other

    @Test
    @DisplayName("an unknown path returns a 404 in the same error format")
    void unknownPathReturnsJsonNotFound() throws Exception {
        mockMvc.perform(get("/not-a-real-endpoint"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/not-a-real-endpoint"));
    }

    // ----------------------------------------------------------------- helpers

    /** Creates a short URL through the API and returns the generated short code. */
    private String createShortUrl(String url) throws Exception {
        return createShortUrlRecord(url).get("shortCode").asText();
    }

    /** Creates a short URL through the API and returns the whole response body. */
    private JsonNode createShortUrlRecord(String url) throws Exception {
        MvcResult result = mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("url", url)))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** Builds a one-property JSON body without string-concatenation escaping mistakes. */
    private String json(String field, String value) throws Exception {
        return objectMapper.writeValueAsString(objectMapper.createObjectNode().put(field, value));
    }
}
