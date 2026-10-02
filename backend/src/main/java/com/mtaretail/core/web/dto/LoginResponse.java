package com.mtaretail.core.web.dto;

/**
 * @param outletId carried in the token so a cashier's transactions are scoped
 *                 to the outlet they physically work at
 */
public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        String username,
        String role,
        Long outletId) {

    public static LoginResponse bearer(String token, long ttl, String username, String role, Long outletId) {
        return new LoginResponse(token, "Bearer", ttl, username, role, outletId);
    }
}