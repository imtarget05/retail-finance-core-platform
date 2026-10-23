package com.mtaretail.core.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Customer account.
 *
 * <p>{@code balance} is a cached derived value kept correct through
 * {@link #applyMovement(long)} under optimistic locking (the {@code version}
 * column). The transaction ledger remains the audit trail of record.
 */
@Entity
@Table(name = "account")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_no", nullable = false, unique = true, length = 32)
    private String accountNo;

    @Column(name = "customer_name", nullable = false, length = 128)
    private String customerName;

    /** SHA-256 of the national ID: we never store the raw identity number. */
    @Column(name = "id_number_hash", nullable = false, unique = true,
            length = 64, columnDefinition = "CHAR(64)")
    private String idNumberHash;

    @Column(nullable = false)
    private long balance;

    @Column(nullable = false, length = 24)
    private String status = "ACTIVE";

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    protected Account() {
        // for JPA
    }

    public Account(String accountNo, String customerName, String idNumberHash) {
        this.accountNo = accountNo;
        this.customerName = customerName;
        this.idNumberHash = idNumberHash;
        this.balance = 0L;
        this.status = "ACTIVE";
    }

    /**
     * Applies a signed balance movement.
     *
     * @param signedAmount positive to credit, negative to debit
     * @throws IllegalStateException if the resulting balance would go negative
     */
    public void applyMovement(long signedAmount) {
        long next = Math.addExact(this.balance, signedAmount);
        if (next < 0) {
            throw new IllegalStateException(
                    "Insufficient balance: account %s has %d, needs %d"
                            .formatted(accountNo, this.balance, -signedAmount));
        }
        this.balance = next;
    }

    public Long getId() {
        return id;
    }

    public String getAccountNo() {
        return accountNo;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getIdNumberHash() {
        return idNumberHash;
    }

    public long getBalance() {
        return balance;
    }

    public String getStatus() {
        return status;
    }

    public Long getVersion() {
        return version;
    }
}
