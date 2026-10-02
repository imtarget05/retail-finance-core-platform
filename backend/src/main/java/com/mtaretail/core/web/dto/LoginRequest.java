package com.mtaretail.core.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "username is required")
        @Size(max = 64)
        String username,

        @NotBlank(message = "password is required")
        @Size(min = 8, max = 128, message = "password must be 8-128 characters")
        String password) {
}