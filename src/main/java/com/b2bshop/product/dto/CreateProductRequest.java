package com.b2bshop.product.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
 *
 * <p>{@code sku} is stored normalized (trimmed, upper-cased), so the raw input
 * is validated here and the normalized form is length-checked by
 * {@link com.b2bshop.product.ProductService} before it reaches the database.
 * Control characters (Unicode category Cc) are rejected: U+0000 cannot be
 * stored by PostgreSQL at all and other control characters are not valid SKU
 * content.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateProductRequest(
        @NotBlank @Size(max = SKU_MAX_LENGTH)
        @Pattern(regexp = "\\P{Cc}*", message = "must not contain control characters") String sku,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 2000) String description,
        @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal price,
        @NotNull @Min(0) Integer stockQuantity) {

    /**
     * Maximum SKU length applied to the raw input and to the normalized value
     * that is stored; mirrors {@code products.sku VARCHAR(64)} in
     * {@code V2__create_products.sql}.
     */
    public static final int SKU_MAX_LENGTH = 64;
}
