package com.mtaretail.core.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies the V1 -> V2 upgrade never infers historical ownership. */
class AccountOutletUpgradeIT {
    @Test
    void leavesAllLegacyAccountsUnresolvedEvenAfterCashierReassignment() throws Exception {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
                .withDatabaseName("upgrade_test").withUsername("test").withPassword("test")) {
            mysql.start();
            Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .locations("classpath:db/migration").target(MigrationVersion.fromVersion("1"))
                    .load().migrate();
            try (Connection connection = DriverManager.getConnection(mysql.getJdbcUrl(),
                    mysql.getUsername(), mysql.getPassword()); Statement sql = connection.createStatement()) {
                sql.executeUpdate("INSERT INTO outlet (id,code,name,province_code) VALUES "
                        + "(1,'A','Outlet A','HCM'),(2,'B','Outlet B','HN')");
                sql.executeUpdate("INSERT INTO app_user (username,password_hash,full_name,role,outlet_id) "
                        + "VALUES ('cashier-a','x','Cashier A','POS_STAFF',1)");
                sql.executeUpdate("INSERT INTO account (id,account_no,customer_name,id_number_hash) VALUES "
                        + "(1,'GOOD','Customer',REPEAT('a',64)),"
                        + "(2,'MISMATCH','Customer',REPEAT('b',64)),"
                        + "(3,'ZERO','Customer',REPEAT('c',64)),"
                        + "(4,'AMBIGUOUS','Customer',REPEAT('d',64)),"
                        + "(5,'REASSIGNED','Customer',REPEAT('e',64))");
                sql.executeUpdate("INSERT INTO txn_transaction "
                        + "(txn_ref,account_id,outlet_id,type,amount,fee,status,idempotency_key) VALUES "
                        + "('TXN-GOOD',1,1,'CASH_IN',100,0,'SETTLED','opening-GOOD'),"
                        + "('TXN-MISMATCH',2,2,'CASH_IN',100,0,'SETTLED','opening-MISMATCH'),"
                        + "('TXN-AMBIGUOUS',4,1,'CASH_IN',100,0,'SETTLED','opening-AMBIGUOUS'),"
                        + "('TXN-REASSIGNED',5,2,'CASH_IN',100,0,'SETTLED','opening-REASSIGNED')");
                sql.executeUpdate("INSERT INTO audit_log (actor,actor_role,action,target_type,target_id) VALUES "
                        + "('cashier-a','POS_STAFF','ACCOUNT_OPENED','Account','GOOD'),"
                        + "('cashier-a','POS_STAFF','ACCOUNT_OPENED','Account','MISMATCH'),"
                        + "('cashier-a','POS_STAFF','ACCOUNT_OPENED','Account','ZERO'),"
                        + "('cashier-a','POS_STAFF','ACCOUNT_OPENED','Account','AMBIGUOUS'),"
                        + "('cashier-a','POS_STAFF','ACCOUNT_OPENED','Account','AMBIGUOUS'),"
                        + "('cashier-a','POS_STAFF','ACCOUNT_OPENED','Account','REASSIGNED')");
                // V1 did not preserve actor outlet history. A later reassignment
                // makes the old opening movement appear to match the current user.
                sql.executeUpdate("UPDATE app_user SET outlet_id=2 WHERE username='cashier-a'");
            }
            Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .locations("classpath:db/migration").load().migrate();
            try (Connection connection = DriverManager.getConnection(mysql.getJdbcUrl(),
                    mysql.getUsername(), mysql.getPassword()); Statement sql = connection.createStatement()) {
                var rows = sql.executeQuery("SELECT account_no, outlet_id FROM account ORDER BY id");
                rows.next();
                assertEquals("GOOD", rows.getString(1));
                assertEquals(null, rows.getObject(2));
                rows.next();
                assertEquals("MISMATCH", rows.getString(1));
                assertEquals(null, rows.getObject(2));
                rows.next();
                assertEquals("ZERO", rows.getString(1));
                assertEquals(null, rows.getObject(2));
                rows.next();
                assertEquals("AMBIGUOUS", rows.getString(1));
                assertEquals(null, rows.getObject(2));
                rows.next();
                assertEquals("REASSIGNED", rows.getString(1));
                assertEquals(null, rows.getObject(2));
            }
        }
    }
}
