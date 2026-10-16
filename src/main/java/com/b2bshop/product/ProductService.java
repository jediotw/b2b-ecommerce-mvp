package com.b2bshop.product;

import java.util.Locale;

import com.b2bshop.product.dto.CreateProductRequest;
import com.b2bshop.product.dto.ProductPageResponse;
import com.b2bshop.product.dto.ProductResponse;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

@Service
public class ProductService {

    /**
     * Name of the unique constraint that backs SKU uniqueness; must match the
     * V2 Flyway migration exactly.
     */
    static final String SKU_UNIQUE_CONSTRAINT = "products_sku_unique";

    private static final String UNIQUE_VIOLATION_SQLSTATE = "23505";

    private final ProductRepository repository;

    private final String currency;

    public ProductService(ProductRepository repository, @Value("${shop.catalog.currency}") String currency) {
        Assert.hasText(currency, "shop.catalog.currency must be configured with a non-blank value");
        this.repository = repository;
        this.currency = currency;
    }

    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        String sku = normalizeSku(request.sku());
        if (repository.existsBySku(sku)) {
            throw new DuplicateSkuException(sku);
        }
        Product product = new Product(
                sku, request.name(), request.description(), request.price(), request.stockQuantity());
        try {
            // saveAndFlush surfaces constraint violations within this operation
            // instead of at transaction commit, so they can be translated here.
            Product saved = repository.saveAndFlush(product);
            return ProductResponse.from(saved, currency);
        }
        catch (DataIntegrityViolationException ex) {
            if (isDuplicateSkuViolation(ex)) {
                throw new DuplicateSkuException(sku);
            }
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public ProductResponse getById(Long id) {
        Product product = repository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
        return ProductResponse.from(product, currency);
    }

    @Transactional(readOnly = true)
    public ProductPageResponse list(int page, int size) {
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        return ProductPageResponse.from(repository.findAll(pageable), currency);
    }

    private static String normalizeSku(String sku) {
        return sku.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Duplicate SKU is identified through the PostgreSQL driver's structured error
     * data: SQLState {@code 23505} (unique_violation) together with the specific
     * constraint name. Exception messages are not parsed, and no other integrity
     * violation is ever classified as a duplicate SKU.
     */
    private static boolean isDuplicateSkuViolation(DataIntegrityViolationException ex) {
        PSQLException psqlException = findCause(ex, PSQLException.class);
        if (psqlException == null) {
            return false;
        }
        ServerErrorMessage serverError = psqlException.getServerErrorMessage();
        if (serverError == null) {
            return false;
        }
        String sqlState = serverError.getSQLState();
        String constraint = serverError.getConstraint();
        return UNIQUE_VIOLATION_SQLSTATE.equals(sqlState) && SKU_UNIQUE_CONSTRAINT.equals(constraint);
    }

    private static <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
        }
        return null;
    }
}
