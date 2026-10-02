package com.mtaretail.core.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * Issues and validates HS256 JWTs.
 *
 * <p>The role claim is what Spring Security maps to authorities, so token
 * contents and authorization decisions share one source of truth.
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    private final SecretKey key;
    private final long ttlSeconds;

    public JwtTokenProvider(@Value("${app.jwt.secret}") String secret,
                            @Value("${app.jwt.ttl-seconds:3600}") long ttlSeconds) {
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        if (raw.length < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret must be at least 32 bytes for HS256, was " + raw.length);
        }
        this.key = Keys.hmacShaKeyFor(raw);
        this.ttlSeconds = ttlSeconds;
    }

    public String issue(String username, String role, Long outletId) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .setSubject(username)
                .claim("role", role)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plusSeconds(ttlSeconds)));
        if (outletId != null) {
            builder.claim("outletId", outletId);
        }
        return builder.signWith(key).compact();
    }

    /**
     * Parses and verifies a token.
     *
     * @return the claims, or null when the token is malformed, tampered or expired
     */
    public Claims parse(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Rejected JWT: {}", ex.getMessage());
            return null;
        }
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }
}