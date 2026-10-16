package com.b2bshop.product.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Create-product payload.
 *
 * <p>Currency is deliberately absent: it is server-configured via
 * {@code shop.catalog.currency} and clients cannot submit it. Unknown JSON
 * properties (misspelled fields, a client-supplied currency, ...) are rejected
 * because {@code spring.jackson.deserialization.fail-on-unknown-properties} is
 * enabled; Jackson 3 disables that feature by default and a per-class
 * {@code @JsonIgnoreProperties(ignoreUnknown = false)} cannot re-enable it.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateProductRequest(
        @NotBlank @Size(max = 64) String sku,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 2000) String description,
        @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal price,
        @NotNull @Min(0) Integer stockQuantity) {
}
