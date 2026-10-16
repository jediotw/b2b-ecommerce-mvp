package com.b2bshop.product;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(Long id) {
        super("Product " + id + " not found");
    }
}
