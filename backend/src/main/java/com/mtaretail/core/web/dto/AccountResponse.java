package com.mtaretail.core.web.dto;

import com.mtaretail.core.domain.Account;

import java.time.Instant;

public record AccountResponse(
        String accountNo,
        String customerName,
        long balance,
        String status,
        Instant createdAt) {

    public static AccountResponse from(Account a) {
        return new AccountResponse(
                a.getAccountNo(), a.getCustomerName(), a.getBalance(),
                a.getStatus(), a.getCreatedAt());
    }
}