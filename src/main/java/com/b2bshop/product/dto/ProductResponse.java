package com.b2bshop.product.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.b2bshop.product.Product;

public record ProductResponse(
        Long id,
        String sku,
        String name,
        String description,
        BigDecimal price,
        String currency,
        int stockQuantity,
        Instant createdAt) {

    public static ProductResponse from(Product product, String currency) {
        return new ProductResponse(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                currency,
                product.getStockQuantity(),
                product.getCreatedAt());
    }
}
