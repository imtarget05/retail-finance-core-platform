package com.mtaretail.core.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A money movement submitted by a POS terminal.
 *
 * <p>{@code idempotencyKey} should be generated on the terminal and reused on
 * retry: it is what stops a flaky connection from double-debiting a customer.
 */
public record PostTransactionRequest(
        @NotNull(message = "type is required")
        String type,

        @NotBlank(message = "accountNo is required")
        @Size(max = 32)
        String accountNo,

        @NotBlank(message = "outletCode is required")
        @Size(max = 32)
        String outletCode,

        @Min(value = 1, message = "amount must be at least 1 VND")
        long amount,

        @Min(value = 0, message = "fee must not be negative")
        long fee,

        @Size(max = 80)
        String idempotencyKey) {
}