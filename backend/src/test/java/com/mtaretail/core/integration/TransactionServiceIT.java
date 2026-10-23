package com.mtaretail.core.integration;

import com.mtaretail.core.domain.AuditLog;
import com.mtaretail.core.domain.TxnStatus;
import com.mtaretail.core.domain.TxnTransaction;
import com.mtaretail.core.domain.TxnType;
import com.mtaretail.core.exception.NotFoundException;
import com.mtaretail.core.exception.IdempotencyConflictException;
import com.mtaretail.core.repository.AuditLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * Money movement rules, proven against real MySQL.
 *
 * <p>Each test here covers a claim that would be unverifiable with an
 * in-memory database, because the guarantee lives in the database itself.
 */
@DisplayName("Transaction posting against real MySQL")
class TransactionServiceIT extends AbstractTransactionIT {

    @SpyBean
    private AuditLogRepository auditLogRepository;

    @Test
    @DisplayName("credit moves the balance up and settles the transaction")
    void creditSettlesAndMovesBalance() {
        long before = balance();

        TxnTransaction txn = transactionService.post(
                TxnType.CASH_IN, ACCOUNT, OUTLET, 250_000L, 0L, uniqueKey("credit"), actor);

        assertEquals(TxnStatus.SETTLED, txn.getStatus());
        assertEquals(before + 250_000L, balance());
        assertTrue(txn.getTxnRef().startsWith("TXN-"));
    }

    @Test
    @DisplayName("the fee is netted off a credit payout")
    void feeIsAppliedInAdditionToAmount() {
        long before = balance();

        transactionService.post(TxnType.CASH_IN, ACCOUNT, OUTLET, 100_000L, 5_000L,
                uniqueKey("fee"), actor);

        assertEquals(before + 100_000L - 5_000L, balance(),
                "customer receives amount minus fee");
    }

    @Test
    @DisplayName("replaying an idempotency key resolves to the original transaction")
    void idempotentReplayMovesMoneyOnce() {
        String key = uniqueKey("idem");
        long before = balance();

        TxnTransaction first = transactionService.post(
                TxnType.CASH_IN, ACCOUNT, OUTLET, 77_000L, 0L, key, actor);
        TxnTransaction replay = transactionService.post(
                TxnType.CASH_IN, ACCOUNT, OUTLET, 77_000L, 0L, key, actor);

        assertEquals(first.getTxnRef(), replay.getTxnRef(),
                "a retry must resolve to the original transaction");
        assertEquals(before + 77_000L, balance(),
                "a retry must not move money a second time");
    }

    @Test
    @DisplayName("same key with different money movement is rejected")
    void idempotencyKeyCannotBeReusedForDifferentAmount() {
        String key = uniqueKey("idem-conflict");
        long before = balance();
        transactionService.post(TxnType.CASH_IN, ACCOUNT, OUTLET, 77_000L, 0L, key, actor);

        assertThrows(IdempotencyConflictException.class, () -> transactionService.post(
                TxnType.CASH_IN, ACCOUNT, OUTLET, 99_000L, 0L, key, actor));
        assertEquals(before + 77_000L, balance());
        assertEquals(1L, txnRepository.findAll().stream()
                .filter(t -> key.equals(t.getIdempotencyKey())).count());
    }

    @Test
    @DisplayName("distinct keys are not collapsed together")
    void distinctKeysCreateDistinctTransactions() {
        long before = balance();

        transactionService.post(TxnType.CASH_IN, ACCOUNT, OUTLET, 1_000L, 0L, uniqueKey("k1"), actor);
        transactionService.post(TxnType.CASH_IN, ACCOUNT, OUTLET, 1_000L, 0L, uniqueKey("k2"), actor);

        assertEquals(before + 2_000L, balance());
    }

    @Test
    @DisplayName("a refused debit leaves no partial state behind")
    void overdraftIsRefusedAndLeavesNoTrace() {
        long before = balance();
        long rowsBefore = txnRepository.count();

        assertThrows(IllegalStateException.class, () -> transactionService.post(
                TxnType.CASH_OUT, ACCOUNT, OUTLET, before + 1L, 0L, uniqueKey("overdraft"), actor));

        assertEquals(before, balance(), "balance must be untouched after a refused debit");
        assertEquals(rowsBefore, txnRepository.count(),
                "the rolled-back transaction must not leave a ledger row");
    }

    @Test
    @DisplayName("the fee counts toward affordability")
    void feeCanTipABalanceIntoOverdraft() {
        long before = balance();

        assertThrows(IllegalStateException.class, () -> transactionService.post(
                TxnType.CASH_OUT, ACCOUNT, OUTLET, before, 1L, uniqueKey("fee-tip"), actor));

        assertEquals(before, balance());
    }

    @Test
    @DisplayName("unknown account or outlet is rejected")
    void unknownReferencesFail() {
        assertThrows(NotFoundException.class, () -> transactionService.post(
                TxnType.CASH_IN, "NO-SUCH-ACCOUNT", OUTLET, 1_000L, 0L, uniqueKey("nf-acc"), actor));

        assertThrows(NotFoundException.class, () -> transactionService.post(
                TxnType.CASH_IN, ACCOUNT, "NO-SUCH-OUTLET", 1_000L, 0L, uniqueKey("nf-out"), actor));
    }

    @Test
    @DisplayName("settlement is written to the audit trail")
    void settlementIsAudited() {
        TxnTransaction txn = transactionService.post(
                TxnType.CASH_IN, ACCOUNT, OUTLET, 1_234L, 0L, uniqueKey("audit"), actor);

        List<AuditLog> history = auditService.historyFor("Transaction", txn.getTxnRef());
        assertTrue(history.stream()
                        .anyMatch(entry -> "TXN_SETTLED".equals(entry.getAction())),
                "expected a TXN_SETTLED audit entry for " + txn.getTxnRef()
                        + " but found " + history.stream().map(AuditLog::getAction).toList());
    }

    @Test
    @DisplayName("audit persistence failure rolls back balance and ledger together")
    void auditFailureRollsBackMoneyMovement() {
        long beforeBalance = balance();
        long beforeLedgerRows = txnRepository.count();
        long beforeAuditRows = auditLogRepository.count();
        String key = uniqueKey("audit-failure");
        doThrow(new DataIntegrityViolationException("injected audit write failure"))
                .when(auditLogRepository).save(any(AuditLog.class));
        doThrow(new DataIntegrityViolationException("injected audit write failure"))
                .when(auditLogRepository).saveAndFlush(any(AuditLog.class));

        try {
            assertThrows(DataIntegrityViolationException.class, () -> transactionService.post(
                    TxnType.CASH_IN, ACCOUNT, OUTLET, 1_234L, 0L, key, actor));
        } finally {
            reset(auditLogRepository);
        }

        assertEquals(beforeBalance, balance(), "audit failure must not change money");
        assertEquals(beforeLedgerRows, txnRepository.count(), "audit failure must not leave a ledger row");
        assertEquals(beforeAuditRows, auditLogRepository.count(), "audit failure must not leave an audit row");
    }

    @Test
    @DisplayName("settlement appears in the reconciliation feed")
    void settledWindowFeedIncludesTheTransaction() {
        TxnTransaction txn = transactionService.post(
                TxnType.PAYMENT, ACCOUNT, OUTLET, 2_000L, 100L, uniqueKey("window"), actor);

        List<TxnTransaction> window = transactionService.settledBetween(
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(60), 100, 0);

        assertTrue(window.stream().anyMatch(t -> t.getTxnRef().equals(txn.getTxnRef())),
                "the settled feed must include the transaction just posted");
    }

    @Test
    @DisplayName("settlement feed exposes consecutive pages without dropping rows")
    void settledWindowFeedPagesAllTransactions() {
        Instant from = Instant.now().minusSeconds(5);
        TxnTransaction first = transactionService.post(
                TxnType.CASH_IN, ACCOUNT, OUTLET, 100L, 0L, uniqueKey("page-1"), actor);
        TxnTransaction second = transactionService.post(
                TxnType.CASH_IN, ACCOUNT, OUTLET, 200L, 0L, uniqueKey("page-2"), actor);
        Instant to = Instant.now().plusSeconds(5);

        List<TxnTransaction> fullFeed = transactionService.settledBetween(from, to, 1000, 0);
        List<String> refs = new ArrayList<>();
        for (int page = 0; page <= fullFeed.size(); page++) {
            List<TxnTransaction> slice = transactionService.settledBetween(from, to, 1, page);
            if (slice.isEmpty()) {
                break;
            }
            refs.add(slice.get(0).getTxnRef());
        }
        assertEquals(fullFeed.stream().map(TxnTransaction::getTxnRef).toList(), refs);
        assertTrue(refs.contains(first.getTxnRef()));
        assertTrue(refs.contains(second.getTxnRef()));
    }

    @Test
    @DisplayName("a settled transaction can no longer be failed")
    void failedTransactionKeepsItsReason() {
        TxnTransaction txn = transactionService.post(
                TxnType.CASH_IN, ACCOUNT, OUTLET, 500L, 0L, uniqueKey("fail"), actor);

        assertThrows(IllegalStateException.class,
                () -> transactionService.markFailed(txn.getTxnRef(), "partner timeout", actor),
                "settling is final; a late failure must be refused");
    }
}
