package com.mtaretail.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Retail Finance Core - transaction processing platform for retail outlets.
 *
 * <p>Owns the system of record for: outlets, customer accounts, money movements,
 * idempotent transaction intake and an append-only audit trail. Reconciliation
 * against partner statements lives in the companion Python service.
 */
@SpringBootApplication
@EnableJpaAuditing
public class RetailFinanceCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(RetailFinanceCoreApplication.class, args);
    }
}