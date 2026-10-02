package com.mtaretail.core.web.dto;

import com.mtaretail.core.domain.TxnStatus;
import com.mtaretail.core.domain.TxnTransaction;

import java.time.Instant;

public record TransactionResponse(
        String txnRef,
        String accountNo,
        String outletCode,
        String type,
        long amount,
        long fee,
        long signedAmount,
        String status,
        String idempotencyKey,
        String partnerRef,
        Instant settledAt,
        Instant createdAt) {

    public static TransactionResponse from(TxnTransaction t, String accountNo, String outletCode) {
        return new TransactionResponse(
                t.getTxnRef(),
                accountNo,
                outletCode,
                t.getType().name(),
                t.getAmount(),
                t.getFee(),
                t.getType().signed(t.getAmount()),
                t.getStatus().name(),
                t.getIdempotencyKey(),
                t.getPartnerRef(),
                t.getSettledAt(),
                t.getCreatedAt());
    }

    public static String statusOf(TxnStatus status) {
        return status.name();
    }
}