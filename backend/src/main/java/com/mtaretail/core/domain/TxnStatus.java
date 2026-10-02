package com.mtaretail.core.domain;

/** Lifecycle of a transaction. Append-only ledger: PENDING -> SETTLED|FAILED. */
public enum TxnStatus {
    PENDING,
    SETTLED,
    FAILED
}