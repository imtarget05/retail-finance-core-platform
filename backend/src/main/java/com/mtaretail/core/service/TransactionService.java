package com.mtaretail.core.service;

import com.mtaretail.core.domain.*;
import com.mtaretail.core.exception.NotFoundException;
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
 *   <li><b>Exactly-once effect</b> - the {@code idempotency_key} unique index
 *       turns a retry into a read of the original transaction instead of a
 *       second debit.</li>
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

    public TransactionService(TxnTransactionRepository txnRepository,
                              AccountRepository accountRepository,
                              OutletRepository outletRepository,
                              AuditService auditService) {
        this.txnRepository = txnRepository;
        this.accountRepository = accountRepository;
        this.outletRepository = outletRepository;
        this.auditService = auditService;
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
    @Transactional(isolation = Isolation.READ_COMMITTED)
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

        TxnTransaction replay = txnRepository.findByIdempotencyKey(effectiveKey).orElse(null);
        if (replay != null) {
            log.info("Idempotent replay key={} -> {}", effectiveKey, replay.getTxnRef());
            auditService.record(actor, "TXN_REPLAY_IGNORED", "Transaction",
                    replay.getTxnRef(), "Duplicate submission ignored");
            return replay;
        }

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

        try {
            txnRepository.saveAndFlush(txn);
        } catch (DataIntegrityViolationException ex) {
            // A concurrent request carried the same key; return the winning row.
            TxnTransaction winner = txnRepository.findByIdempotencyKey(effectiveKey).orElse(null);
            if (winner != null) {
                log.info("Concurrent duplicate key={}, winner {}", effectiveKey, winner.getTxnRef());
                return winner;
            }
            throw ex;
        }

        account.applyMovement(type.signed(amount));
        if (fee > 0) {
            account.applyMovement(-fee);
        }
        accountRepository.save(account);

        txn.settle();
        txnRepository.save(txn);

        auditService.record(actor, "TXN_SETTLED", "Transaction", txnRef,
                "type=%s amount=%d fee=%d balance=%d"
                        .formatted(type, amount, fee, account.getBalance()));
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
