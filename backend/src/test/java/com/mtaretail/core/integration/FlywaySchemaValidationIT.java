package com.mtaretail.core.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates database migration integrity and schema consistency against real MySQL.
 *
 * <p>Enforces two gates:
 * <ul>
 *   <li><b>Flyway validate:</b> asserts migration scripts match applied schema with
 *       zero checksum drift, no missing migrations, and valid versions.</li>
 *   <li><b>Hibernate ddl-auto validate:</b> verified automatically during Spring Boot
 *       context startup; fails the build if JPA entity definitions diverge from the
 *       Flyway-managed tables.</li>
 * </ul>
 */
@DisplayName("Flyway migration integrity and drift gate against real MySQL")
class FlywaySchemaValidationIT extends AbstractTransactionIT {

    @Autowired
    private Flyway flyway;

    @Test
    @DisplayName("flyway validate passes with zero checksum drift")
    void flywayValidate_confirmsNoDrift() {
        assertDoesNotThrow(() -> flyway.validate(),
                "Flyway migrations must match applied schema with zero checksum drift");

        MigrationInfo[] applied = flyway.info().applied();
        assertTrue(applied.length > 0, "at least one migration must be applied to the database");
    }
}
