package com.urlshorty.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Describes the API itself - the heading, version and summary shown at the top of Swagger UI and in
 * the generated {@code /v3/api-docs} document.
 *
 * <p>Everything else in that document is derived from the code: the routes come from the Spring MVC
 * annotations, the request and response shapes from the DTO classes, and the descriptions from the
 * {@code @Operation} and {@code @ApiResponse} annotations on the controller. This class only fills in
 * the parts that cannot be inferred.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI urlshortyOpenApi() {
        return new OpenAPI().info(new Info()
                .title("urlshorty")
                .version("1.0.0")
                .description("""
                        A URL shortening service. It turns a long URL into a short code, resolves the
                        code back to the original URL, and reports how often each code has been used.

                        Codes are seven random Base62 characters, generated with SecureRandom and
                        guaranteed unique by a database constraint.

                        Note: GET /shorten/{shortCode} returns JSON, not a redirect. Looking the URL
                        up and sending the browser onwards is the frontend's responsibility.""")
                .license(new License().name("Not yet chosen")));
    }
}
