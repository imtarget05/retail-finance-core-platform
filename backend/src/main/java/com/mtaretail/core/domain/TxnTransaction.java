package com.mtaretail.core.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Ledger entry. One row per money movement, never deleted.
 *
 * <p>The {@code idempotencyKey} carries a unique index: a POS terminal that
 * retries over a flaky link re-sends the same key and gets the original
 * transaction back instead of a second debit.
 */
@Entity
@Table(name = "txn_transaction")
public class TxnTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "txn_ref", nullable = false, unique = true, length = 40)
    private String txnRef;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, columnDefinition = "VARCHAR(16)")
    private TxnType type;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    private long fee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24, columnDefinition = "VARCHAR(24)")
    private TxnStatus status = TxnStatus.PENDING;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 80)
    private String idempotencyKey;

    @Column(name = "partner_ref", length = 64)
    private String partnerRef;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    public Instant getSettledAt() {
        return settledAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    protected TxnTransaction() {
        // for JPA
    }

    public TxnTransaction(String txnRef,
                          Long accountId,
                          Long outletId,
                          TxnType type,
                          long amount,
                          long fee,
                          String idempotencyKey) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive, got " + amount);
        }
        if (fee < 0) {
            throw new IllegalArgumentException("fee must not be negative, got " + fee);
        }
        this.txnRef = txnRef;
        this.accountId = accountId;
        this.outletId = outletId;
        this.type = type;
        this.amount = amount;
        this.fee = fee;
        this.idempotencyKey = idempotencyKey;
        this.status = TxnStatus.PENDING;
    }

    /** Marks the transaction settled; only valid while PENDING. */
    public void settle() {
        if (status != TxnStatus.PENDING) {
            throw new IllegalStateException("only PENDING transactions can settle, was " + status);
        }
        this.status = TxnStatus.SETTLED;
        this.settledAt = Instant.now();
    }

    /** Marks the transaction failed, recording why. */
    public void fail(String reason) {
        if (status != TxnStatus.PENDING) {
            throw new IllegalStateException("only PENDING transactions can fail, was " + status);
        }
        this.status = TxnStatus.FAILED;
        this.failureReason = reason;
    }

    public Long getId() {
        return id;
    }

    public String getTxnRef() {
        return txnRef;
    }

    public Long getAccountId() {
        return accountId;
    }

    public Long getOutletId() {
        return outletId;
    }

    public TxnType getType() {
        return type;
    }

    public long getAmount() {
        return amount;
    }

    public long getFee() {
        return fee;
    }

    public TxnStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getPartnerRef() {
        return partnerRef;
    }

    public void setPartnerRef(String partnerRef) {
        this.partnerRef = partnerRef;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}