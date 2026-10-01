package com.urlshorty.controller;

import com.urlshorty.dto.ShortenRequest;
import com.urlshorty.dto.StatsResponse;
import com.urlshorty.dto.UrlResponse;
import com.urlshorty.service.UrlShortenerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.urlshorty.dto.ApiError;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * HTTP layer of the API. Every method does the same three things: read the request, delegate to
 * {@link UrlShortenerService} and translate the result into a status code plus a body.
 */
@RestController
@RequestMapping("/shorten")
@Tag(name = "Short URLs", description = "Create, read, update, delete and measure short URLs")
public class UrlController {

    private final UrlShortenerService service;

    public UrlController(UrlShortenerService service) {
        this.service = service;
    }

    /**
     * {@code POST /shorten} - creates a short URL.
     *
     * <p>Returns 201 Created, the new resource in the body, and a {@code Location} header pointing at
     * it - the detail that makes the response self-describing for a REST client.
     */
    @PostMapping
    @Operation(summary = "Create a new short URL")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Short URL created",
                    headers = @Header(name = "Location", description = "Path of the new short URL",
                            schema = @Schema(type = "string")),
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = UrlResponse.class))),
            @ApiResponse(responseCode = "400", description = "The url is missing, too long, or not an absolute http(s) URL",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<UrlResponse> create(@Valid @RequestBody ShortenRequest request) {
        UrlResponse created = service.create(request.url());
        return ResponseEntity
                .created(URI.create("/shorten/" + created.shortCode()))
                .body(created);
    }

    /**
     * {@code GET /shorten/{shortCode}} - returns the record for a short code.
     *
     * <p>The specification deliberately returns JSON here instead of a 301 redirect: looking the URL
     * up and redirecting the browser is the frontend's responsibility.
     */
    @GetMapping("/{shortCode}")
    @Operation(summary = "Retrieve the original URL for a short code")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The short URL was found; this access was counted",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = UrlResponse.class))),
            @ApiResponse(responseCode = "404", description = "No short URL exists for this code",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))
    })
    public UrlResponse retrieve(@PathVariable String shortCode) {
        return service.resolve(shortCode);
    }

    /** {@code PUT /shorten/{shortCode}} - repoints a short code at a different long URL. */
    @PutMapping("/{shortCode}")
    @Operation(summary = "Update the original URL of a short code")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The short URL now points at the new URL",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = UrlResponse.class))),
            @ApiResponse(responseCode = "400", description = "The submitted url is missing, too long, or not an absolute http(s) URL",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No short URL exists for this code",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))
    })
    public UrlResponse update(@PathVariable String shortCode,
                              @Valid @RequestBody ShortenRequest request) {
        return service.update(shortCode, request.url());
    }

    /** {@code DELETE /shorten/{shortCode}} - removes a short code. Returns 204 with no body. */
    @DeleteMapping("/{shortCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a short code")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "The short code was deleted; the response has no body"),
            @ApiResponse(responseCode = "404", description = "No short URL exists for this code",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))
    })
    public void delete(@PathVariable String shortCode) {
        service.delete(shortCode);
    }

    /** {@code GET /shorten/{shortCode}/stats} - returns the record plus its access count. */
    @GetMapping("/{shortCode}/stats")
    @Operation(summary = "Get access statistics for a short code")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The record plus how often the code was resolved",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = StatsResponse.class))),
            @ApiResponse(responseCode = "404", description = "No short URL exists for this code",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))
    })
    public StatsResponse stats(@PathVariable String shortCode) {
        return service.stats(shortCode);
    }
}
