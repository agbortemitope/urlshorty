package com.urlshorty.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Custom bean-validation constraint: the annotated value must be an absolute {@code http} or
 * {@code https} URL with a host part.
 *
 * <p>A dedicated constraint is used instead of a regular expression because regular expressions are
 * notoriously bad at validating URLs. {@link HttpUrlValidator} parses the value with
 * {@link java.net.URI} and inspects the parsed pieces instead.
 */
@Documented
@Constraint(validatedBy = HttpUrlValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidHttpUrl {

    String message() default "must be an absolute http or https URL, for example https://example.com/page";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
