package com.mtaretail.core.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Direction of money movement is a rule, so it gets tested as one. */
class TxnTypeTest {

    @ParameterizedTest(name = "{0} is credit = {1}")
    @CsvSource({
            "CASH_IN, true",
            "TRANSFER_IN, true",
            "PAYMENT, false",
            "CASH_OUT, false",
            "TRANSFER_OUT, false"
    })
    void creditFlagMatchesDomainMeaning(String type, boolean expectedCredit) {
        assertEquals(expectedCredit, TxnType.valueOf(type).isCredit());
    }

    @ParameterizedTest(name = "signed amount for {0} with 100000 is {1}")
    @CsvSource({
            "CASH_IN, 100000",
            "TRANSFER_IN, 100000",
            "PAYMENT, -100000",
            "CASH_OUT, -100000",
            "TRANSFER_OUT, -100000"
    })
    void signFollowsDirection(String type, long expected) {
        assertEquals(expected, TxnType.valueOf(type).signed(100_000L));
    }

    @Test
    @DisplayName("amount is always stored positive; the type carries the direction")
    void amountIsNeverSigned() {
        assertTrue(TxnType.PAYMENT.signed(1) < 0);
        assertTrue(TxnType.CASH_IN.signed(1) > 0);
    }

    @Test
    @DisplayName("a transaction cannot settle twice")
    void settleIsOneWay() {
        TxnTransaction txn = new TxnTransaction("TXN-1", 1L, 1L,
                TxnType.CASH_IN, 1000L, 0L, "key-1");

        txn.settle();
        assertEquals(TxnStatus.SETTLED, txn.getStatus());
        assertTrue(txn.getSettledAt() != null);

        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, txn::settle).getMessage().contains("PENDING"));
    }

    @Test
    @DisplayName("a settled transaction cannot be failed afterwards")
    void settledCannotBeFailed() {
        TxnTransaction txn = new TxnTransaction("TXN-2", 1L, 1L,
                TxnType.CASH_IN, 1000L, 0L, "key-2");
        txn.settle();
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> txn.fail("too late")).getMessage()
                .contains("PENDING"));
    }

    @Test
    @DisplayName("amount and fee are validated at construction")
    void rejectsInvalidAmounts() {
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new TxnTransaction("TXN-3", 1L, 1L, TxnType.CASH_IN, 0L, 0L, "k"))
                .getMessage().contains("amount must be positive"));

        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new TxnTransaction("TXN-4", 1L, 1L, TxnType.CASH_IN, 100L, -1L, "k"))
                .getMessage().contains("fee must not be negative"));
    }

    @Test
    @DisplayName("a new transaction starts PENDING")
    void startsPending() {
        TxnTransaction txn = new TxnTransaction("TXN-5", 1L, 1L,
                TxnType.PAYMENT, 500L, 10L, "k");
        assertEquals(TxnStatus.PENDING, txn.getStatus());
        assertFalse(txn.getPartnerRef() != null);
    }
}