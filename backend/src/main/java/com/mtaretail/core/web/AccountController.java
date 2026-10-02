package com.mtaretail.core.web;

import com.mtaretail.core.domain.Account;
import com.mtaretail.core.exception.BusinessRuleException;
import com.mtaretail.core.exception.NotFoundException;
import com.mtaretail.core.repository.AccountRepository;
import com.mtaretail.core.repository.OutletRepository;
import com.mtaretail.core.service.AuditService;
import com.mtaretail.core.service.TransactionService;
import com.mtaretail.core.web.dto.AccountResponse;
import com.mtaretail.core.web.dto.CreateAccountRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts", description = "Customer account lifecycle")
public class AccountController {

    private final AccountRepository accountRepository;
    private final OutletRepository outletRepository;
    private final TransactionService transactionService;
    private final AuditService auditService;

    public AccountController(AccountRepository accountRepository,
                             OutletRepository outletRepository,
                             TransactionService transactionService,
                             AuditService auditService) {
        this.accountRepository = accountRepository;
        this.outletRepository = outletRepository;
        this.transactionService = transactionService;
        this.auditService = auditService;
    }

    @Operation(summary = "Open an account",
            description = "The national ID is stored only as a SHA-256 hash; "
                    + "opening balance is seeded by a synthetic CASH_IN movement.")
    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request,
                                                 Authentication authentication) {
        if (request.openingBalance() < 0) {
            throw new IllegalArgumentException("openingBalance must not be negative");
        }
        if (accountRepository.findByAccountNo(request.accountNo()).isPresent()) {
            throw new BusinessRuleException("Account already exists: " + request.accountNo());
        }

        String idHash = TransactionService.hashIdNumber(request.idNumber());
        Account account = new Account(request.accountNo(), request.customerName(), idHash);
        Account saved = accountRepository.save(account);

        // Seed the opening balance through the ledger rather than by writing the
        // balance column directly, so the account history explains every dong.
        if (request.openingBalance() > 0) {
            transactionService.post(
                    com.mtaretail.core.domain.TxnType.CASH_IN,
                    saved.getAccountNo(),
                    firstOutletCode(),
                    request.openingBalance(),
                    0L,
                    "opening-" + saved.getAccountNo(),
                    authentication);
            saved = accountRepository.findByAccountNo(saved.getAccountNo()).orElseThrow();
        }

        auditService.record(authentication, "ACCOUNT_OPENED", "Account",
                saved.getAccountNo(), "openingBalance=" + request.openingBalance());

        return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponse.from(saved));
    }

    @Operation(summary = "Read an account with its current balance")
    @GetMapping("/{accountNo}")
    public ResponseEntity<AccountResponse> get(@PathVariable String accountNo) {
        Account account = accountRepository.findByAccountNo(accountNo)
                .orElseThrow(() -> new NotFoundException("Account not found: " + accountNo));
        return ResponseEntity.ok(AccountResponse.from(account));
    }

    /**
     * Opening a balance needs a real outlet to attribute the movement to.
     */
    private String firstOutletCode() {
        return outletRepository.findAll().stream()
                .findFirst()
                .map(com.mtaretail.core.domain.Outlet::getCode)
                .orElseThrow(() -> new BusinessRuleException(
                        "No outlet exists yet; create an outlet before opening accounts"));
    }
}