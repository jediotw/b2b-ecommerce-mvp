package com.b2bshop.product;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.b2bshop.product.dto.CreateProductRequest;
import com.b2bshop.product.dto.ProductResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack tests against a real PostgreSQL Testcontainer (Flyway V1+V2,
 * Hibernate {@code ddl-auto=validate}, MockMvc through the real DispatcherServlet).
 */
@SpringBootTest
@Testcontainers
class ProductApiIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    WebApplicationContext applicationContext;

    @Autowired
    ProductService productService;

    @MockitoSpyBean
    ProductRepository productRepository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        jdbcTemplate.update("delete from products");
    }

    @Test
    void createProductReturns201LocationAndPersistsNormalizedRow() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":" sku-001 ","name":"Hex bolt M8","description":"M8x30",
                                 "price":12.50,"stockQuantity":100}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern("/api/v1/products/\\d+")))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.sku").value("SKU-001"))
                .andExpect(jsonPath("$.name").value("Hex bolt M8"))
                .andExpect(jsonPath("$.description").value("M8x30"))
                .andExpect(jsonPath("$.price").value(12.5))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.stockQuantity").value(100))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select sku, name, price, stock_quantity, created_at from products where sku = 'SKU-001'");
        assertThat(row.get("sku")).isEqualTo("SKU-001");
        assertThat(row.get("name")).isEqualTo("Hex bolt M8");
        assertThat((BigDecimal) row.get("price")).isEqualByComparingTo("12.50");
        assertThat(row.get("stock_quantity")).isEqualTo(100);
        assertThat(row.get("created_at")).isNotNull();
    }

    @Test
    void createProductRejectsDuplicateSkuWith409() throws Exception {
        String body = """
                {"sku":"SKU-100","name":"Bolt","price":1.00,"stockQuantity":5}
                """;
        mockMvc.perform(post("/api/v1/products").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/products").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value(containsString("SKU-100")))
                .andExpect(jsonPath("$.instance").value("/api/v1/products"));
    }

    @Test
    void createProductRejectsDuplicateAfterSkuNormalization() throws Exception {
        mockMvc.perform(post("/api/v1/products").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"SKU-200","name":"Bolt","price":1.00,"stockQuantity":5}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/products").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":" sku-200 ","name":"Bolt again","price":1.00,"stockQuantity":5}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("SKU-200")));
    }

    @Test
    void createProductRejectsUnknownJsonProperty() throws Exception {
        mockMvc.perform(post("/api/v1/products").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"SKU-300","name":"Bolt","price":1.00,"stckQuantity":5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.errors.stckQuantity").value("unknown property"));
    }

    @Test
    void createProductRejectsClientSuppliedCurrency() throws Exception {
        mockMvc.perform(post("/api/v1/products").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"SKU-301","name":"Bolt","price":1.00,"stockQuantity":5,"currency":"USD"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.currency").value("unknown property"));
    }

    @Test
    void createProductRejectsMissingStockQuantity() throws Exception {
        mockMvc.perform(post("/api/v1/products").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"SKU-302","name":"Bolt","price":1.00}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.errors.stockQuantity").value("must not be null"));
    }

    @Test
    void createProductRejectsBlankFieldsAndNegativeValues() throws Exception {
        mockMvc.perform(post("/api/v1/products").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"   ","name":"","price":-1.00,"stockQuantity":-5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.sku").exists())
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.price").exists())
                .andExpect(jsonPath("$.errors.stockQuantity").exists());
    }

    @Test
    void createProductRejectsPriceWithMoreThanTwoDecimals() throws Exception {
        mockMvc.perform(post("/api/v1/products").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"SKU-303","name":"Bolt","price":1.999,"stockQuantity":1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.price").exists());
    }

    @Test
    void getProductReturnsProduct() throws Exception {
        ProductResponse created = productService.create(
                new CreateProductRequest("SKU-400", "Nut M8", null, new BigDecimal("3.25"), 25));

        mockMvc.perform(get("/api/v1/products/{id}", created.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.id().intValue()))
                .andExpect(jsonPath("$.sku").value("SKU-400"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.stockQuantity").value(25));
    }

    @Test
    void getUnknownProductReturns404ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/products/999999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail").value("Product 999999 not found"))
                .andExpect(jsonPath("$.instance").value("/api/v1/products/999999"));
    }

    @Test
    void getProductWithNonNumericIdReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/products/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Invalid value for parameter 'id'"));
    }

    @Test
    void listProductsAppliesPaginationAndFixedSort() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            ids.add(productService.create(new CreateProductRequest(
                    "SKU-50" + i, "Item " + i, null, new BigDecimal(i + ".00"), i)).id());
        }

        // Defaults: page 0, size 20.
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content.length()").value(5));

        // Newest first; ties broken by id descending.
        mockMvc.perform(get("/api/v1/products").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.content[0].id").value(ids.get(4).intValue()))
                .andExpect(jsonPath("$.content[1].id").value(ids.get(3).intValue()));

        // Last partial page.
        mockMvc.perform(get("/api/v1/products").param("page", "2").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(ids.get(0).intValue()));

        // Page beyond the end: empty content, still 200.
        mockMvc.perform(get("/api/v1/products").param("page", "5").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void listRejectsOutOfRangePaginationParameters() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.size").exists());

        mockMvc.perform(get("/api/v1/products").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.page").exists());
    }

    @Test
    void nonSkuIntegrityViolationsAreNotTranslatedToDuplicateSku() {
        // Bypasses web-layer validation on purpose: the CHECK constraint must reject
        // negative stock at the database, and the failure must stay a generic
        // integrity violation instead of being mislabeled as a duplicate SKU.
        CreateProductRequest negativeStock =
                new CreateProductRequest("SKU-600", "Broken", null, new BigDecimal("1.00"), -5);

        assertThatThrownBy(() -> productService.create(negativeStock))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateSkuException.class);

        // A genuine duplicate still surfaces as the dedicated conflict.
        productService.create(new CreateProductRequest("SKU-601", "Item", null, new BigDecimal("1.00"), 1));
        assertThatThrownBy(() -> productService.create(
                new CreateProductRequest("SKU-601", "Item again", null, new BigDecimal("2.00"), 2)))
                .isInstanceOf(DuplicateSkuException.class);
    }

    @Test
    void realUniqueConstraintViolationIsTranslatedToDuplicateSku() {
        // Persist the "winning" row through the production path.
        productService.create(new CreateProductRequest(
                "SKU-700", "Existing item", null, new BigDecimal("1.00"), 1));

        // Simulate the race the unique constraint guards against: the pre-check misses,
        // so the database itself rejects the duplicate insert with SQLState 23505.
        doReturn(false).when(productRepository).existsBySku("SKU-700");

        assertThatThrownBy(() -> productService.create(new CreateProductRequest(
                "SKU-700", "Racing item", null, new BigDecimal("2.00"), 2)))
                .isInstanceOf(DuplicateSkuException.class)
                .hasMessage("Product with SKU 'SKU-700' already exists");

        // The same insert performed directly through the repository must surface the
        // genuine PostgreSQL violation: the real Hibernate/pgjdbc chain contains
        // PSQLException with the unique-violation SQLState and the exact constraint
        // name the translation predicate relies on.
        Throwable thrown = catchThrowable(() -> productRepository.saveAndFlush(
                new Product("SKU-700", "Racing item", null, new BigDecimal("2.00"), 2)));
        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        DataIntegrityViolationException violation = (DataIntegrityViolationException) thrown;
        PSQLException psqlException = causeOfType(violation, PSQLException.class);
        // PSQLException is an SQLException, hence Iterable<Throwable>: cast so AssertJ
        // picks the Throwable overload instead of the ambiguous Iterable overload.
        assertThat((Throwable) psqlException).isNotNull();
        ServerErrorMessage serverError = psqlException.getServerErrorMessage();
        assertThat(serverError).isNotNull();
        assertThat(serverError.getSQLState()).isEqualTo("23505");
        assertThat(serverError.getConstraint()).isEqualTo("products_sku_unique");

        // Isolation: clean up and leave no rows behind.
        jdbcTemplate.update("delete from products where sku = 'SKU-700'");
        Integer remaining = jdbcTemplate.queryForObject("select count(*) from products", Integer.class);
        assertThat(remaining).isZero();
    }

    @Test
    void v2MigrationAppliedAndSchemaMatchesSpec() {
        Boolean v2Success = jdbcTemplate.queryForObject(
                "select success from flyway_schema_history where version = '2'", Boolean.class);
        assertThat(v2Success).isTrue();

        List<String> columns = jdbcTemplate.queryForList("""
                select column_name from information_schema.columns
                where table_schema = 'public' and table_name = 'products'
                order by ordinal_position
                """, String.class);
        assertThat(columns)
                .containsExactly("id", "sku", "name", "description", "price", "stock_quantity", "created_at");

        List<String> constraints = jdbcTemplate.queryForList("""
                select conname from pg_constraint where conrelid = 'products'::regclass order by conname
                """, String.class);
        assertThat(constraints).contains(
                "products_sku_unique", "products_price_non_negative", "products_stock_non_negative");

        // Hibernate schema validation (ddl-auto=validate) has implicitly passed:
        // the application context would not have started otherwise.
    }

    private static <T extends Throwable> T causeOfType(Throwable throwable, Class<T> type) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
        }
        return null;
    }
}
