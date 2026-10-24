package com.mtaretail.core.integration;

import com.mtaretail.core.domain.Account;
import com.mtaretail.core.domain.TxnStatus;
import com.mtaretail.core.domain.TxnTransaction;
import com.mtaretail.core.domain.TxnType;
import com.mtaretail.core.service.TransactionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level concurrency tests exercising optimistic locking (@Version)
 * and race conditions during concurrent debits against the same account.
 *
 * <p>Proves two critical invariants under concurrent load against real MySQL:
 * <ol>
 *   <li>No lost updates: balance is decremented exactly by the sum of settled debits.</li>
 *   <li>Optimistic concurrency control: conflicting concurrent debits trigger
 *       {@link OptimisticLockingFailureException} and roll back completely.</li>
 * </ol>
 */
@DisplayName("Account debit concurrency and optimistic locking against real MySQL")
class AccountDebitConcurrencyIT extends AbstractTransactionIT {

    @Test
    @DisplayName("concurrent requests with one idempotency key return one settled row")
    void concurrentRetriesSettleOnce() throws Exception {
        String key = uniqueKey("same-key-race");
        long before = balance();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TxnTransaction>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return transactionService.post(TxnType.CASH_IN, ACCOUNT, OUTLET,
                            12_345L, 0L, key, actor);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            String firstRef = results.get(0).get(15, TimeUnit.SECONDS).getTxnRef();
            assertEquals(firstRef, results.get(1).get(15, TimeUnit.SECONDS).getTxnRef());
            assertEquals(before + 12_345L, balance());
            assertEquals(1L, txnRepository.findAll().stream()
                    .filter(t -> key.equals(t.getIdempotencyKey())).count());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("competing debits for the last unit: exactly one succeeds and optimistic lock fails the rest")
    void concurrentDebitsCompetingForLastUnit_exactlyOneSucceeds() throws Exception {
        String testAccount = "COMP-ACC-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        accountRepository.save(new Account(
                testAccount, "Compete Customer", TransactionService.hashIdNumber("ID-" + testAccount),
                outletRepository.findByCode(OUTLET).orElseThrow().getId()));

        // Seed account with exactly 100,000 VND
        transactionService.post(TxnType.CASH_IN, testAccount, OUTLET, 100_000L, 0L,
                uniqueKey("compete-seed"), actor);
        long startingBalance = accountRepository.findByAccountNo(testAccount).orElseThrow().getBalance();
        assertEquals(100_000L, startingBalance, "initial balance must be 100,000");

        int concurrency = 8;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch readyLatch = new CountDownLatch(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger concurrencyFailures = new AtomicInteger(0);
        AtomicInteger insufficientBalanceFailures = new AtomicInteger(0);
        List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < concurrency; i++) {
            final String debitKey = uniqueKey("compete-debit-" + i);
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    transactionService.post(
                            TxnType.CASH_OUT, testAccount, OUTLET, 100_000L, 0L, debitKey, actor);
                    successCount.incrementAndGet();
                } catch (org.springframework.dao.TransientDataAccessException ex) {
                    concurrencyFailures.incrementAndGet();
                } catch (IllegalStateException ex) {
                    if (ex.getMessage() != null && ex.getMessage().contains("Insufficient balance")) {
                        insufficientBalanceFailures.incrementAndGet();
                    } else {
                        unexpectedErrors.add(ex);
                    }
                } catch (Throwable t) {
                    // Inspect root cause for concurrency conflict or lock exceptions
                    if (isConcurrencyConflictException(t)) {
                        concurrencyFailures.incrementAndGet();
                    } else {
                        unexpectedErrors.add(t);
                    }
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // Release all threads simultaneously
        executor.shutdown();
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS), "threads must finish within budget");

        assertTrue(unexpectedErrors.isEmpty(), "no unexpected exceptions: " + unexpectedErrors);
        assertEquals(1, successCount.get(), "exactly one debit must succeed when balance allows only one");
        assertEquals(concurrency - 1, concurrencyFailures.get() + insufficientBalanceFailures.get(),
                "all other threads must be rejected");
        assertTrue(concurrencyFailures.get() > 0,
                "concurrency control must reject at least one concurrent conflict: found " + concurrencyFailures.get());

        // Assert database state invariants
        long finalBalance = accountRepository.findByAccountNo(testAccount).orElseThrow().getBalance();
        assertEquals(0L, finalBalance, "final balance must be exactly 0 (no overdraft, no lost update)");

        Account account = accountRepository.findByAccountNo(testAccount).orElseThrow();
        assertTrue(account.getVersion() > 0, "account @Version must have advanced");
    }

    @Test
    @DisplayName("concurrent debits with sufficient balance: no lost updates and balance strictly matches settlements")
    void concurrentDebits_noLostUpdates() throws Exception {
        String testAccount = "MULTI-ACC-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        accountRepository.save(new Account(
                testAccount, "Multi Customer", TransactionService.hashIdNumber("ID-" + testAccount),
                outletRepository.findByCode(OUTLET).orElseThrow().getId()));

        // Seed account with 500,000 VND
        transactionService.post(TxnType.CASH_IN, testAccount, OUTLET, 500_000L, 0L,
                uniqueKey("multi-seed"), actor);
        long startingBalance = accountRepository.findByAccountNo(testAccount).orElseThrow().getBalance();

        int concurrency = 10;
        long debitAmount = 50_000L; // 10 x 50,000 = 500,000 maximum possible debits
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch readyLatch = new CountDownLatch(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < concurrency; i++) {
            final String debitKey = uniqueKey("multi-debit-" + i);
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    transactionService.post(
                            TxnType.CASH_OUT, testAccount, OUTLET, debitAmount, 0L, debitKey, actor);
                    successCount.incrementAndGet();
                } catch (org.springframework.dao.TransientDataAccessException ex) {
                    conflictCount.incrementAndGet();
                } catch (Throwable t) {
                    if (isConcurrencyConflictException(t)) {
                        conflictCount.incrementAndGet();
                    } else {
                        unexpectedErrors.add(t);
                    }
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS), "threads must finish within budget");

        assertTrue(unexpectedErrors.isEmpty(), "no unexpected exceptions: " + unexpectedErrors);
        assertEquals(concurrency, successCount.get() + conflictCount.get(),
                "every thread must either succeed or register a concurrency conflict");

        // Mathematical invariant: Final Balance = Starting Balance - (successCount * debitAmount)
        long finalBalance = accountRepository.findByAccountNo(testAccount).orElseThrow().getBalance();
        long expectedBalance = startingBalance - (successCount.get() * debitAmount);
        assertEquals(expectedBalance, finalBalance,
                "balance must exactly reflect settled debits with zero lost updates");
        assertTrue(finalBalance >= 0, "balance must never go negative");
    }

    private static boolean isConcurrencyConflictException(Throwable t) {
        while (t != null) {
            if (t instanceof OptimisticLockingFailureException
                    || t instanceof org.springframework.dao.TransientDataAccessException
                    || t instanceof jakarta.persistence.OptimisticLockException
                    || t.getClass().getSimpleName().contains("OptimisticLock")
                    || t.getClass().getSimpleName().contains("Lock")
                    || (t.getMessage() != null && t.getMessage().contains("Deadlock"))) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
