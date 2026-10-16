package com.b2bshop.product.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import com.b2bshop.product.Product;

public record ProductPageResponse(
        List<ProductResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static ProductPageResponse from(Page<Product> products, String currency) {
        List<ProductResponse> content = products.getContent().stream()
                .map(product -> ProductResponse.from(product, currency))
                .toList();
        return new ProductPageResponse(
                content,
                products.getNumber(),
                products.getSize(),
                products.getTotalElements(),
                products.getTotalPages());
    }
}
