package com.mtaretail.core.web;

import com.mtaretail.core.domain.Account;
import com.mtaretail.core.exception.NotFoundException;
import com.mtaretail.core.repository.AccountRepository;
import com.mtaretail.core.service.AccountOpeningService;
import com.mtaretail.core.service.ActorOutletPolicy;
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
    private final AccountOpeningService accountOpeningService;
    private final ActorOutletPolicy actorOutletPolicy;

    public AccountController(AccountRepository accountRepository,
                             AccountOpeningService accountOpeningService,
                             ActorOutletPolicy actorOutletPolicy) {
        this.accountRepository = accountRepository;
        this.accountOpeningService = accountOpeningService;
        this.actorOutletPolicy = actorOutletPolicy;
    }

    @Operation(summary = "Open an account",
            description = "The national ID is stored only as a SHA-256 hash; "
                    + "opening balance is seeded by a synthetic CASH_IN movement.")
    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request,
                                                 Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AccountResponse.from(accountOpeningService.open(request, authentication)));
    }

    @Operation(summary = "Read an account with its current balance")
    @GetMapping("/{accountNo}")
    public ResponseEntity<AccountResponse> get(@PathVariable String accountNo, Authentication authentication) {
        Account account = accountRepository.findByAccountNo(accountNo)
                .orElseThrow(() -> new NotFoundException("Account not found: " + accountNo));
        actorOutletPolicy.requireAccountRead(authentication, account);
        return ResponseEntity.ok(AccountResponse.from(account));
    }

}
