package com.b2bshop;

import java.sql.Connection;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ShopApplicationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void flywayBaselineAppliedAgainstTestcontainer() throws Exception {
        // Flyway must have applied the baseline migration to the fresh test database.
        Boolean success = jdbcTemplate.queryForObject(
                "select success from flyway_schema_history where version = '1'", Boolean.class);
        assertThat(success).isTrue();

        // The live JDBC connection must target the ephemeral Testcontainers port,
        // proving the test does not use the Compose PostgreSQL instance.
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL())
                    .contains(":" + postgres.getMappedPort(5432));
        }
    }
}
