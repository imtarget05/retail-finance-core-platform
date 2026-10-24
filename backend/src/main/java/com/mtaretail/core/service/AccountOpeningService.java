package com.mtaretail.core.service;

import com.mtaretail.core.domain.Account;
import com.mtaretail.core.domain.Outlet;
import com.mtaretail.core.domain.TxnType;
import com.mtaretail.core.exception.BusinessRuleException;
import com.mtaretail.core.repository.AccountRepository;
import com.mtaretail.core.repository.OutletRepository;
import com.mtaretail.core.web.dto.CreateAccountRequest;
import org.springframework.security.core.Authentication;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.hibernate.exception.ConstraintViolationException;

@Service
public class AccountOpeningService {

    private final AccountRepository accountRepository;
    private final OutletRepository outletRepository;
    private final TransactionService transactionService;
    private final AuditService auditService;
    private final ActorOutletPolicy actorOutletPolicy;
    private final TransactionTemplate openingTransaction;

    public AccountOpeningService(AccountRepository accountRepository,
                                 OutletRepository outletRepository,
                                 TransactionService transactionService,
                                 AuditService auditService,
                                 ActorOutletPolicy actorOutletPolicy,
                                 PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.outletRepository = outletRepository;
        this.transactionService = transactionService;
        this.auditService = auditService;
        this.actorOutletPolicy = actorOutletPolicy;
        this.openingTransaction = new TransactionTemplate(transactionManager);
    }

    /** Account, opening ledger movement, and both audit rows commit as one unit. */
    public Account open(CreateAccountRequest request, Authentication actor) {
        try {
            return openingTransaction.execute(status -> openInTransaction(request, actor));
        } catch (DataIntegrityViolationException ex) {
            // Classify only the two account uniqueness invariants. An audit or
            // ledger integrity failure must retain its original exception.
            String constraint = violatedConstraint(ex);
            if ("uk_account_no".equals(constraint)) {
                // The insert has rolled back; read the winner in a fresh scope.
                if (accountRepository.findByAccountNo(request.accountNo()).isPresent()) {
                    throw new BusinessRuleException("Account already exists: " + request.accountNo());
                }
            } else if ("uk_account_id_number_hash".equals(constraint)) {
                throw new BusinessRuleException("Customer identity already has an account");
            }
            throw ex;
        }
    }

    private static String violatedConstraint(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                String name = violation.getConstraintName();
                if (name != null) {
                    return name.substring(name.lastIndexOf('.') + 1);
                }
            }
        }
        return null;
    }

    private Account openInTransaction(CreateAccountRequest request, Authentication actor) {
        Long actorOutletId = actorOutletPolicy.requirePostingOutlet(actor);
        if (request.openingBalance() < 0) {
            throw new IllegalArgumentException("openingBalance must not be negative");
        }
        if (accountRepository.findByAccountNo(request.accountNo()).isPresent()) {
            throw new BusinessRuleException("Account already exists: " + request.accountNo());
        }

        Account account = new Account(request.accountNo(), request.customerName(),
                TransactionService.hashIdNumber(request.idNumber()), actorOutletId);
        Account saved = accountRepository.save(account);

        if (request.openingBalance() > 0) {
            transactionService.post(TxnType.CASH_IN, saved.getAccountNo(), actorOutletCode(actorOutletId),
                    request.openingBalance(), 0L, "opening-" + saved.getAccountNo(), actor);
            saved = accountRepository.findByAccountNo(saved.getAccountNo()).orElseThrow();
        }

        auditService.recordWithAccountOpening(actor, "ACCOUNT_OPENED", "Account",
                saved.getAccountNo(), "openingBalance=" + request.openingBalance());
        return saved;
    }

    private String actorOutletCode(Long actorOutletId) {
        return outletRepository.findById(actorOutletId)
                .map(Outlet::getCode)
                .orElseThrow(() -> new BusinessRuleException("Actor outlet is unavailable"));
    }
}
