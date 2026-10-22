package com.mtaretail.core;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the suite against a real MySQL 8 rather than an in-memory substitute.
 *
 * <p>Deliberate: the behaviour under test - unique-index idempotency, CHECK
 * constraints, optimistic locking, transactional rollback - is enforced by
 * MySQL itself. H2 would happily pass tests that fail in production.
 */
public abstract class AbstractMySqlIT {

    public static final MySQLContainer<?> MYSQL;

    static {
        MYSQL = new MySQLContainer<>("mysql:8.0")
                .withDatabaseName("retail_finance")
                .withUsername("test")
                .withPassword("test");
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("app.seed.enabled", () -> "false");
        registry.add("app.jwt.secret", () -> "test-secret-key-that-is-definitely-long-enough-0123456789");
    }

    @TestConfiguration
    static class NoOpConfig {
        @Bean
        @Primary
        org.springframework.security.crypto.password.PasswordEncoder passwordEncoder() {
            return new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4);
        }
    }
}