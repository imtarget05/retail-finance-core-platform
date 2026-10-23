package com.mtaretail.core.service;

import com.mtaretail.core.domain.Account;
import com.mtaretail.core.repository.AccountRepository;
import com.mtaretail.core.repository.OutletRepository;
import com.mtaretail.core.web.dto.CreateAccountRequest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountOpeningServiceTest {

    @Test
    void unrelatedIntegrityFailureIsNotDisguisedAsDuplicateAccount() {
        AccountRepository accounts = mock(AccountRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        Account winner = new Account("A-1", "Other", TransactionService.hashIdNumber("123456789"));
        when(accounts.findByAccountNo("A-1")).thenReturn(Optional.empty(), Optional.of(winner));
        DataIntegrityViolationException unrelatedFailure =
                new DataIntegrityViolationException("injected audit integrity failure");
        when(accounts.save(any(Account.class))).thenThrow(unrelatedFailure);
        AccountOpeningService service = new AccountOpeningService(accounts,
                mock(OutletRepository.class), mock(TransactionService.class),
                mock(AuditService.class), transactionManager);

        DataIntegrityViolationException actual = assertThrows(DataIntegrityViolationException.class,
                () -> service.open(new CreateAccountRequest("A-1", "Customer", "987654321", 0L), null));
        assertSame(unrelatedFailure, actual);
    }
}
