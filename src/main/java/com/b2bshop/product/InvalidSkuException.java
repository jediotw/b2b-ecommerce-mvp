package com.b2bshop.product;

import com.b2bshop.product.dto.CreateProductRequest;

public class InvalidSkuException extends RuntimeException {

    public InvalidSkuException(String sku, int normalizedLength) {
        super("Product with SKU '" + sku + "' normalizes to " + normalizedLength
                + " characters, exceeding the maximum of " + CreateProductRequest.SKU_MAX_LENGTH);
    }
}
