package com.mtaretail.core.service;

import com.mtaretail.core.domain.*;
import com.mtaretail.core.exception.NotFoundException;
import com.mtaretail.core.exception.IdempotencyConflictException;
import com.mtaretail.core.repository.AccountRepository;
import com.mtaretail.core.repository.OutletRepository;
import com.mtaretail.core.repository.TxnTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Moves money and records it in the ledger.
 *
 * <p>Two correctness properties are load-bearing:
 * <ul>
 *   <li><b>One effect per key</b> - the {@code idempotency_key} unique index
 *       prevents a second ledger row; replay validates the original payload.</li>
 *   <li><b>No lost updates</b> - the account row is guarded by optimistic
 *       locking ({@code @Version}), so two concurrent debits cannot both read
 *       the same balance and overwrite each other.</li>
 * </ul>
 */
@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);
    private static final int MAX_PAGE = 1000;

    private final TxnTransactionRepository txnRepository;
    private final AccountRepository accountRepository;
    private final OutletRepository outletRepository;
    private final AuditService auditService;
    private final TransactionTemplate postingTransaction;

    public TransactionService(TxnTransactionRepository txnRepository,
                              AccountRepository accountRepository,
                              OutletRepository outletRepository,
                              AuditService auditService,
                              PlatformTransactionManager transactionManager) {
        this.txnRepository = txnRepository;
        this.accountRepository = accountRepository;
        this.outletRepository = outletRepository;
        this.auditService = auditService;
        this.postingTransaction = new TransactionTemplate(transactionManager);
        this.postingTransaction.setIsolationLevel(Isolation.READ_COMMITTED.value());
    }
/**
     * Records a money movement and applies it to the account balance.
     *
     * <p>Isolated at READ_COMMITTED: the balance row is protected by optimistic
     * locking, which makes this sufficient and avoids the lock pile-up that
     * SERIALIZABLE would cause across unrelated accounts.
     *
     * @return the created transaction, or the pre-existing one on retry
     */
    public TxnTransaction post(TxnType type,
                               String accountNo,
                               String outletCode,
                               long amount,
                               long fee,
                               String idempotencyKey,
                               Authentication actor) {

        String effectiveKey = (idempotencyKey == null || idempotencyKey.isBlank())
                ? UUID.randomUUID().toString()
                : idempotencyKey;

        if (type == null || amount <= 0 || fee < 0) {
            throw new IllegalArgumentException("Invalid money movement");
        }

        TxnTransaction replay = txnRepository.findByIdempotencyKey(effectiveKey).orElse(null);
        if (replay != null) {
            return acceptReplay(replay, type, accountNo, outletCode, amount, fee, actor);
        }

        try {
            TxnTransaction posted = postingTransaction.execute(status ->
                    postNew(type, accountNo, outletCode, amount, fee, effectiveKey));
            auditService.record(actor, "TXN_SETTLED", "Transaction", posted.getTxnRef(),
                    "type=%s amount=%d fee=%d".formatted(type, amount, fee));
            return posted;
        } catch (DataIntegrityViolationException ex) {
            // The failed insert's transaction has rolled back. The winner is
            // now read in a fresh transaction, never in a rollback-only scope.
            TxnTransaction winner = txnRepository.findByIdempotencyKey(effectiveKey).orElse(null);
            if (winner == null) {
                throw ex;
            }
            return acceptReplay(winner, type, accountNo, outletCode, amount, fee, actor);
        }
    }

    private TxnTransaction acceptReplay(TxnTransaction replay, TxnType type,
                                        String accountNo, String outletCode,
                                        long amount, long fee, Authentication actor) {
        Long accountId = accountRepository.findByAccountNo(accountNo).map(Account::getId).orElse(null);
        Long outletId = outletRepository.findByCode(outletCode).map(Outlet::getId).orElse(null);
        if (!java.util.Objects.equals(replay.getAccountId(), accountId)
                || !java.util.Objects.equals(replay.getOutletId(), outletId)
                || replay.getType() != type || replay.getAmount() != amount || replay.getFee() != fee) {
            throw new IdempotencyConflictException();
        }
        log.info("Idempotent replay -> {}", replay.getTxnRef());
        auditService.record(actor, "TXN_REPLAY_IGNORED", "Transaction",
                replay.getTxnRef(), "Duplicate submission ignored");
        return replay;
    }

    private TxnTransaction postNew(TxnType type, String accountNo, String outletCode,
                                  long amount, long fee, String effectiveKey) {

        Account account = accountRepository.findByAccountNo(accountNo)
                .orElseThrow(() -> new NotFoundException("Account not found: " + accountNo));

        if (!"ACTIVE".equals(account.getStatus())) {
            throw new IllegalStateException("Account is not active: " + accountNo);
        }

        Outlet outlet = outletRepository.findByCode(outletCode)
                .orElseThrow(() -> new NotFoundException("Outlet not found: " + outletCode));

        long totalDebit = Math.addExact(amount, fee);
        if (!type.isCredit() && account.getBalance() < totalDebit) {
            throw new IllegalStateException("Insufficient balance: account %s has %d, needs %d"
                    .formatted(accountNo, account.getBalance(), totalDebit));
        }

        String txnRef = generateTxnRef();
        TxnTransaction txn = new TxnTransaction(
                txnRef, account.getId(), outlet.getId(), type, amount, fee, effectiveKey);

        txnRepository.saveAndFlush(txn);

        account.applyMovement(type.signed(amount));
        if (fee > 0) {
            account.applyMovement(-fee);
        }
        accountRepository.saveAndFlush(account);

        txn.settle();
        txnRepository.save(txn);

        return txn;
    }

    /** Marks a pending transaction as failed. Used by the settlement retry path. */
    @Transactional
    public TxnTransaction markFailed(String txnRef, String reason, Authentication actor) {
        TxnTransaction txn = txnRepository.findByTxnRef(txnRef)
                .orElseThrow(() -> new NotFoundException("Transaction not found: " + txnRef));
        txn.fail(reason);
        TxnTransaction saved = txnRepository.save(txn);
        auditService.record(actor, "TXN_FAILED", "Transaction", txnRef, reason);
        return saved;
    }

    @Transactional(readOnly = true)
    public TxnTransaction findByRef(String txnRef) {
        return txnRepository.findByTxnRef(txnRef)
                .orElseThrow(() -> new NotFoundException("Transaction not found: " + txnRef));
    }

    /**
     * Settled movements in a window, paged - the feed the Python reconciliation
     * service matches against partner statements.
     */
    @Transactional(readOnly = true)
    public List<TxnTransaction> settledBetween(Instant from, Instant to, int limit, int page) {
        if (!from.isBefore(to) || limit < 1 || limit > MAX_PAGE || page < 0) {
            throw new IllegalArgumentException("Invalid settled feed window or page");
        }
        return txnRepository.findSettledBetween(
                TxnStatus.SETTLED, from, to, PageRequest.of(page, limit));
    }

    /** Human-readable reference: TXN-20261002-4829137 */
    static String generateTxnRef() {
        int suffix = ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
        return "TXN-%s-%d".formatted(LocalDate.now(), suffix);
    }

    /** SHA-256 hex of a national ID; the raw value is never stored. */
    public static String hashIdNumber(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
