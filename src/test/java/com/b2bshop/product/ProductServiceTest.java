package com.b2bshop.product;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.b2bshop.product.dto.CreateProductRequest;
import com.b2bshop.product.dto.ProductPageResponse;
import com.b2bshop.product.dto.ProductResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-16T10:00:00Z");

    @Mock
    ProductRepository repository;

    ProductService service;

    @BeforeEach
    void setUp() {
        service = new ProductService(repository, "INR");
    }

    @Test
    void createPersistsNormalizedSkuAndReturnsResponse() {
        given(repository.existsBySku("SKU-001")).willReturn(false);
        given(repository.saveAndFlush(any(Product.class))).willAnswer(invocation -> {
            Product product = invocation.getArgument(0);
            ReflectionTestUtils.setField(product, "id", 42L);
            ReflectionTestUtils.setField(product, "createdAt", CREATED_AT);
            return product;
        });

        ProductResponse response = service.create(request(" sku-001 "));

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.sku()).isEqualTo("SKU-001");
        assertThat(response.name()).isEqualTo("Hex bolt M8");
        assertThat(response.description()).isEqualTo("M8x30");
        assertThat(response.price()).isEqualByComparingTo("12.50");
        assertThat(response.currency()).isEqualTo("INR");
        assertThat(response.stockQuantity()).isEqualTo(100);
        assertThat(response.createdAt()).isEqualTo(CREATED_AT);

        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getSku()).isEqualTo("SKU-001");
        assertThat(saved.getValue().getStockQuantity()).isEqualTo(100);
    }

    @Test
    void createRejectsDuplicateSkuFromPreCheckWithoutSaving() {
        given(repository.existsBySku("SKU-001")).willReturn(true);

        assertThatThrownBy(() -> service.create(request(" SKU-001 ")))
                .isInstanceOf(DuplicateSkuException.class)
                .hasMessage("Product with SKU 'SKU-001' already exists");

        verify(repository, never()).saveAndFlush(any(Product.class));
    }

    @Test
    void createTranslatesSkuUniqueConstraintViolationToDuplicateSku() {
        given(repository.existsBySku("SKU-001")).willReturn(false);
        DataIntegrityViolationException violation = integrityViolation("23505", "products_sku_unique");
        given(repository.saveAndFlush(any(Product.class))).willThrow(violation);

        assertThatThrownBy(() -> service.create(request("SKU-001")))
                .isInstanceOf(DuplicateSkuException.class)
                .hasMessage("Product with SKU 'SKU-001' already exists");
    }

    @Test
    void createDoesNotTranslateOtherConstraintViolations() {
        given(repository.existsBySku("SKU-001")).willReturn(false);
        DataIntegrityViolationException violation = integrityViolation("23514", "products_stock_non_negative");
        given(repository.saveAndFlush(any(Product.class))).willThrow(violation);

        assertThatThrownBy(() -> service.create(request("SKU-001")))
                .isSameAs(violation)
                .isNotInstanceOf(DuplicateSkuException.class);
    }

    @Test
    void createDoesNotTranslateWhenPostgresReportsNoServerError() {
        given(repository.existsBySku("SKU-001")).willReturn(false);
        PSQLException psqlException = mock(PSQLException.class);
        given(psqlException.getServerErrorMessage()).willReturn(null);
        DataIntegrityViolationException violation =
                new DataIntegrityViolationException("connection failure", psqlException);
        given(repository.saveAndFlush(any(Product.class))).willThrow(violation);

        assertThatThrownBy(() -> service.create(request("SKU-001")))
                .isSameAs(violation);
    }

    @Test
    void constructorRejectsBlankOrNullCurrency() {
        assertThatThrownBy(() -> new ProductService(repository, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shop.catalog.currency");

        assertThatThrownBy(() -> new ProductService(repository, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shop.catalog.currency");
    }

    @Test
    void getByIdReturnsProduct() {
        given(repository.findById(7L)).willReturn(Optional.of(product(7L, "SKU-007")));

        ProductResponse response = service.getById(7L);

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.sku()).isEqualTo("SKU-007");
        assertThat(response.currency()).isEqualTo("INR");
    }

    @Test
    void getByIdUnknownIdThrowsNotFound() {
        given(repository.findById(404L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(404L))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessage("Product 404 not found");
    }

    @Test
    void listMapsPageAndUsesFixedSort() {
        given(repository.findAll(any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(product(2L, "SKU-002")), PageRequest.of(0, 2), 3));

        ProductPageResponse response = service.list(0, 2);

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).sku()).isEqualTo("SKU-002");
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(3);
        assertThat(response.totalPages()).isEqualTo(2);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(pageable.capture());
        assertThat(pageable.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
    }

    private static CreateProductRequest request(String sku) {
        return new CreateProductRequest(sku, "Hex bolt M8", "M8x30", new BigDecimal("12.50"), 100);
    }

    private static Product product(Long id, String sku) {
        Product product = new Product(sku, "Hex bolt M8", null, new BigDecimal("12.50"), 100);
        ReflectionTestUtils.setField(product, "id", id);
        ReflectionTestUtils.setField(product, "createdAt", CREATED_AT);
        return product;
    }

    private static DataIntegrityViolationException integrityViolation(String sqlState, String constraint) {
        ServerErrorMessage serverError = mock(ServerErrorMessage.class);
        given(serverError.getSQLState()).willReturn(sqlState);
        given(serverError.getConstraint()).willReturn(constraint);
        PSQLException psqlException = mock(PSQLException.class);
        given(psqlException.getServerErrorMessage()).willReturn(serverError);
        return new DataIntegrityViolationException("integrity violation", psqlException);
    }
}
