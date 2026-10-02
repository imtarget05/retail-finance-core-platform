package com.mtaretail.core.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @NotBlank(message = "accountNo is required")
        @Size(max = 32)
        String accountNo,

        @NotBlank(message = "customerName is required")
        @Size(max = 128)
        String customerName,

        @NotBlank(message = "idNumber is required")
        @Size(min = 9, max = 20)
        String idNumber,

        @NotNull(message = "openingBalance is required")
        Long openingBalance) {

    /** 9-12 digits: national ID or citizen ID style. */
    @Pattern(regexp = "^[0-9]{9,12}$", message = "idNumber must be 9-12 digits")
    public String idNumberPattern() {
        return idNumber;
    }
}