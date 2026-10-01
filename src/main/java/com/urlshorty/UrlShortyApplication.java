package com.urlshorty;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the URL shortening service.
 *
 * <p>{@code @SpringBootApplication} enables three things at once: component scanning of the
 * {@code com.urlshorty} package tree, auto-configuration (Spring Boot wiring up Tomcat, Jackson,
 * Hibernate and friends based on what is on the classpath), and the configuration support that reads
 * {@code application.yml}.
 */
@SpringBootApplication
public class UrlShortyApplication {

    public static void main(String[] args) {
        SpringApplication.run(UrlShortyApplication.class, args);
    }
}
