package com.mtaretail.core.domain;

/**
 * Direction of money movement. CREDIT increases the account balance,
 * DEBIT decreases it. Amounts are always positive whole dong; the sign is
 * implied by the type so the ledger stays easy to audit by eye.
 */
public enum TxnType {
    CASH_IN(true),
    TRANSFER_IN(true),
    PAYMENT(false),
    CASH_OUT(false),
    TRANSFER_OUT(false);

    private final boolean credit;

    TxnType(boolean credit) {
        this.credit = credit;
    }

    public boolean isCredit() {
        return credit;
    }

    /** Signed effect on the account balance for this type. */
    public long signed(long amount) {
        return credit ? amount : -amount;
    }
}