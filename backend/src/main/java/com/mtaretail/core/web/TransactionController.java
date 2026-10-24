package com.mtaretail.core.web;

import com.mtaretail.core.domain.TxnTransaction;
import com.mtaretail.core.domain.TxnType;
import com.mtaretail.core.repository.AccountRepository;
import com.mtaretail.core.repository.OutletRepository;
import com.mtaretail.core.service.TransactionService;
import com.mtaretail.core.web.dto.PostTransactionRequest;
import com.mtaretail.core.web.dto.TransactionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/transactions")
@Tag(name = "Transactions", description = "Money movement intake and lookup")
public class TransactionController {

    private final TransactionService transactionService;
    private final AccountRepository accountRepository;
    private final OutletRepository outletRepository;

    public TransactionController(TransactionService transactionService,
                                 AccountRepository accountRepository,
                                 OutletRepository outletRepository) {
        this.transactionService = transactionService;
        this.accountRepository = accountRepository;
        this.outletRepository = outletRepository;
    }

    @Operation(summary = "Post a money movement",
            description = "Idempotent on idempotencyKey: repeating the same key returns "
                    + "the original transaction instead of moving money twice.")
    @PostMapping
    public ResponseEntity<TransactionResponse> post(@Valid @RequestBody PostTransactionRequest request,
                                                  Authentication authentication) {
        TxnType type;
        try {
            type = TxnType.valueOf(request.type());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported transaction type: " + request.type());
        }

        TxnTransaction txn = transactionService.post(
                type,
                request.accountNo(),
                request.outletCode(),
                request.amount(),
                request.fee(),
                request.idempotencyKey(),
                authentication);

        return ResponseEntity.ok(TransactionResponse.from(
                txn, request.accountNo(), request.outletCode()));
    }

    @Operation(summary = "Look up one transaction by its reference")
    @GetMapping("/{txnRef}")
    public ResponseEntity<TransactionResponse> get(@PathVariable String txnRef, Authentication authentication) {
        TxnTransaction txn = transactionService.findByRef(txnRef, authentication);
        String accountNo = accountRepository.findById(txn.getAccountId())
                .map(a -> a.getAccountNo()).orElse(null);
        String outletCode = outletRepository.findById(txn.getOutletId())
                .map(o -> o.getCode()).orElse(null);
        return ResponseEntity.ok(TransactionResponse.from(txn, accountNo, outletCode));
    }

    @Operation(summary = "Settled movements in a window",
            description = "Feed endpoint consumed by the reconciliation service.")
    @GetMapping("/settled")
    public ResponseEntity<List<TransactionResponse>> settled(
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(defaultValue = "0") int page) {

        List<TransactionResponse> body = transactionService.settledBetween(from, to, limit, page).stream()
                .map(t -> TransactionResponse.from(
                        t,
                        accountRepository.findById(t.getAccountId()).map(a -> a.getAccountNo()).orElse(null),
                        outletRepository.findById(t.getOutletId()).map(o -> o.getCode()).orElse(null)))
                .toList();
        return ResponseEntity.ok(body);
    }
}
