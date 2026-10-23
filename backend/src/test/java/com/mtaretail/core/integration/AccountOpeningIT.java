package com.mtaretail.core.integration;

import com.mtaretail.core.domain.AuditLog;
import com.mtaretail.core.domain.TxnTransaction;
import com.mtaretail.core.domain.TxnStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mtaretail.core.repository.AuditLogRepository;
import com.mtaretail.core.repository.TxnTransactionRepository;
import com.mtaretail.core.web.AccountController;
import com.mtaretail.core.web.dto.CreateAccountRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AccountOpeningIT extends AbstractTransactionIT {

    @Autowired
    private AccountController accountController;

    @SpyBean
    private AuditLogRepository auditLogRepository;

    @SpyBean
    private TxnTransactionRepository transactionRepositorySpy;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void zeroOpeningBalanceCreatesOnlyAccountAndOpeningAudit() {
        String accountNo = "OPEN-" + UUID.randomUUID().toString().substring(0, 20);
        CreateAccountRequest normal = request(accountNo);
        CreateAccountRequest zero = new CreateAccountRequest(accountNo, normal.customerName(),
                normal.idNumber(), 0L);
        long ledgerBefore = txnRepository.count();

        var response = accountController.create(zero, actor);

        assertEquals(201, response.getStatusCode().value());
        assertEquals(0L, response.getBody().balance());
        assertEquals(0L, accountRepository.findByAccountNo(accountNo).orElseThrow().getBalance());
        assertEquals(ledgerBefore, txnRepository.count());
        assertTrue(txnRepository.findByIdempotencyKey("opening-" + accountNo).isEmpty());
        assertEquals(1L, auditService.historyFor("Account", accountNo).stream()
                .filter(log -> "ACCOUNT_OPENED".equals(log.getAction())).count());
    }

    @Test
    void anotherAccountCannotReuseRegisteredCustomerIdentity() throws Exception {
        String firstNo = "OPEN-" + UUID.randomUUID().toString().substring(0, 20);
        String secondNo = "OPEN-" + UUID.randomUUID().toString().substring(0, 20);
        CreateAccountRequest first = request(firstNo);
        accountController.create(first, actor);
        long ledgerBefore = txnRepository.count();

        CreateAccountRequest duplicateIdentity = new CreateAccountRequest(secondNo,
                first.customerName(), first.idNumber(), 10_000L);
        mvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicateIdentity))
                        .with(user("it-cashier").roles("POS_STAFF")))
                .andExpect(status().isUnprocessableEntity());

        assertTrue(accountRepository.findByAccountNo(secondNo).isEmpty());
        assertEquals(ledgerBefore, txnRepository.count());
    }

    @Test
    void concurrentDuplicateAccountCreationReturnsClientErrorsWithoutExtraMovement() throws Exception {
        String accountNo = "OPEN-" + UUID.randomUUID().toString().substring(0, 20);
        String json = objectMapper.writeValueAsString(request(accountNo));
        long ledgerBefore = txnRepository.count();
        int concurrency = 8;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> statuses = new ArrayList<>();
        try {
            for (int i = 0; i < concurrency; i++) {
                statuses.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return mvc.perform(post("/api/v1/accounts")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(json)
                                    .with(user("it-cashier").roles("POS_STAFF")))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> status : statuses) {
                results.add(status.get(15, TimeUnit.SECONDS));
            }
            assertEquals(1L, results.stream().filter(status -> status == 201).count(), results.toString());
            assertEquals(concurrency - 1L, results.stream().filter(status -> status == 422).count(),
                    results.toString());
            assertEquals(10_000L, accountRepository.findByAccountNo(accountNo).orElseThrow().getBalance());
            assertEquals(ledgerBefore + 1L, txnRepository.count());
            assertTrue(txnRepository.findByIdempotencyKey("opening-" + accountNo).isPresent());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void successfulOpeningPersistsAccountMovementAndAuditTogether() {
        String accountNo = "OPEN-" + UUID.randomUUID().toString().substring(0, 20);

        var response = accountController.create(request(accountNo), actor);
        TxnTransaction movement = txnRepository.findByIdempotencyKey("opening-" + accountNo)
                .orElseThrow();

        assertEquals(201, response.getStatusCode().value());
        assertEquals(10_000L, response.getBody().balance());
        assertEquals(10_000L, accountRepository.findByAccountNo(accountNo).orElseThrow().getBalance());
        assertEquals(TxnStatus.SETTLED, movement.getStatus());
        assertTrue(auditService.historyFor("Account", accountNo).stream()
                .anyMatch(log -> "ACCOUNT_OPENED".equals(log.getAction())));
        assertTrue(auditService.historyFor("Transaction", movement.getTxnRef()).stream()
                .anyMatch(log -> "TXN_SETTLED".equals(log.getAction())));
    }

    @Test
    void failedOpeningPostingLeavesNoAccountOrLedger() {
        String accountNo = "OPEN-" + UUID.randomUUID().toString().substring(0, 20);
        long ledgerBefore = txnRepository.count();
        doThrow(new DataIntegrityViolationException("injected posting failure"))
                .when(transactionRepositorySpy).saveAndFlush(any(TxnTransaction.class));

        try {
            assertThrows(DataIntegrityViolationException.class,
                    () -> accountController.create(request(accountNo), actor));
        } finally {
            reset(transactionRepositorySpy);
        }

        assertFalse(accountRepository.findByAccountNo(accountNo).isPresent());
        assertEquals(ledgerBefore, txnRepository.count());
        assertTrue(auditService.historyFor("Account", accountNo).isEmpty());
        assertTrue(txnRepository.findByIdempotencyKey("opening-" + accountNo).isEmpty());
    }

    @Test
    void failedOpeningAuditRollsBackAccountLedgerAndBothAuditRows() {
        String accountNo = "OPEN-" + UUID.randomUUID().toString().substring(0, 20);
        long ledgerBefore = txnRepository.count();
        long auditBefore = auditLogRepository.count();
        doThrow(new DataIntegrityViolationException("injected opening audit failure"))
                .when(auditLogRepository).saveAndFlush(argThat(
                        (AuditLog log) -> "ACCOUNT_OPENED".equals(log.getAction())));

        try {
            assertThrows(DataIntegrityViolationException.class,
                    () -> accountController.create(request(accountNo), actor));
        } finally {
            reset(auditLogRepository);
        }

        assertFalse(accountRepository.findByAccountNo(accountNo).isPresent());
        assertEquals(ledgerBefore, txnRepository.count());
        assertEquals(auditBefore, auditLogRepository.count());
        assertTrue(txnRepository.findByIdempotencyKey("opening-" + accountNo).isEmpty());
    }

    private static CreateAccountRequest request(String accountNo) {
        String id = Long.toString(ThreadLocalRandom.current().nextLong(100_000_000_000L,
                1_000_000_000_000L));
        return new CreateAccountRequest(accountNo, "Opening Test", id, 10_000L);
    }
}
