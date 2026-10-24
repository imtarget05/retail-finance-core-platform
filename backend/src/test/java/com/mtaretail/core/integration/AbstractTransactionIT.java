package com.mtaretail.core.integration;

import com.mtaretail.core.AbstractMySqlIT;
import com.mtaretail.core.domain.Account;
import com.mtaretail.core.domain.Outlet;
import com.mtaretail.core.domain.TxnType;
import com.mtaretail.core.domain.AppUser;
import com.mtaretail.core.domain.Role;
import com.mtaretail.core.repository.AccountRepository;
import com.mtaretail.core.repository.OutletRepository;
import com.mtaretail.core.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * Shared wiring for integration tests.
 *
 * <p>Runs against a real MySQL 8 (see {@link AbstractMySqlIT}) because the
 * behaviour under test - unique-index idempotency, CHECK constraints,
 * optimistic locking, rollback - is enforced by MySQL itself.
 */
@SpringBootTest
abstract class AbstractTransactionIT extends AbstractMySqlIT {

    protected static final String OUTLET = "IT-OUTLET-01";
    protected static final String ACCOUNT = "IT-ACC-0001";

    @Autowired
    protected TransactionService transactionService;

    @Autowired
    protected AccountRepository accountRepository;

    @Autowired
    protected OutletRepository outletRepository;

    @Autowired
    protected com.mtaretail.core.repository.AppUserRepository userRepository;

    @Autowired
    protected com.mtaretail.core.repository.TxnTransactionRepository txnRepository;

    @Autowired
    protected com.mtaretail.core.service.AuditService auditService;

    protected Authentication actor;

    @BeforeEach
    void seedFixtures() {
        actor = new UsernamePasswordAuthenticationToken(
                "it-cashier", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_POS_STAFF")));

        if (outletRepository.findByCode(OUTLET).isEmpty()) {
            outletRepository.save(new Outlet(OUTLET, "Integration Outlet", "HCM"));
        }
        Long outletId = outletRepository.findByCode(OUTLET).orElseThrow().getId();
        if (userRepository.findByUsername("it-cashier").isEmpty()) {
            userRepository.save(new AppUser("it-cashier", "unused", "Integration Cashier",
                    Role.POS_STAFF, outletId));
        }
        if (accountRepository.findByAccountNo(ACCOUNT).isEmpty()) {
            accountRepository.save(new Account(
                    ACCOUNT, "Integration Customer",
                    TransactionService.hashIdNumber("ID-" + ACCOUNT), outletId));
        }
    }

    protected long balance() {
        return accountRepository.findByAccountNo(ACCOUNT).orElseThrow().getBalance();
    }

    protected static String uniqueKey(String label) {
        return label + "-" + java.util.UUID.randomUUID();
    }
}
