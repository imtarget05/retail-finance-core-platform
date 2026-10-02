package com.mtaretail.core.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure domain rules - no Spring, no database. These run in milliseconds and
 * pin down the arithmetic that money correctness depends on.
 */
class AccountTest {

    private static Account accountWith(long balance) {
        Account account = new Account("ACC1", "Test", "hash");
        if (balance != 0) {
            account.applyMovement(balance);
        }
        return account;
    }

    @Test
    @DisplayName("new account starts at zero, active")
    void newAccountIsEmptyAndActive() {
        Account account = new Account("ACC1", "Test", "hash");
        assertEquals(0L, account.getBalance());
        assertEquals("ACTIVE", account.getStatus());
    }

    @Test
    @DisplayName("credit increases the balance")
    void creditIncreasesBalance() {
        Account account = accountWith(100_000L);
        account.applyMovement(50_000L);
        assertEquals(150_000L, account.getBalance());
    }

    @Test
    @DisplayName("debit decreases the balance")
    void debitDecreasesBalance() {
        Account account = accountWith(100_000L);
        account.applyMovement(-30_000L);
        assertEquals(70_000L, account.getBalance());
    }

    @Test
    @DisplayName("debiting the exact balance is allowed and lands on zero")
    void debitToExactlyZeroIsAllowed() {
        Account account = accountWith(100_000L);
        account.applyMovement(-100_000L);
        assertEquals(0L, account.getBalance());
    }

    @Test
    @DisplayName("overdraft is rejected and leaves the balance untouched")
    void overdraftIsRejectedAtomically() {
        Account account = accountWith(100_000L);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> account.applyMovement(-100_001L));

        assertTrue(ex.getMessage().contains("Insufficient balance"));
        assertEquals(100_000L, account.getBalance(), "balance must not change on a rejected debit");
    }

    @Test
    @DisplayName("overdraft from a zero-balance account is rejected")
    void overdraftFromZeroIsRejected() {
        Account account = new Account("ACC1", "Test", "hash");
        assertThrows(IllegalStateException.class, () -> account.applyMovement(-1L));
        assertEquals(0L, account.getBalance());
    }

    @ParameterizedTest(name = "credit movements on {0} round-trip exactly")
    @CsvSource({"0", "1", "999", "100000", "999999999999"})
    @DisplayName("VND amounts have no minor unit, so large values stay exact")
    void movementsRoundTripExactly(long amount) {
        Account account = accountWith(amount);
        assertEquals(amount, account.getBalance());
    }
}