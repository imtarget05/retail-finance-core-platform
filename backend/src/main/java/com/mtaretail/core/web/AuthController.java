package com.mtaretail.core.web;

import com.mtaretail.core.domain.AppUser;
import com.mtaretail.core.repository.AppUserRepository;
import com.mtaretail.core.security.JwtTokenProvider;
import com.mtaretail.core.service.AuditService;
import com.mtaretail.core.web.dto.LoginRequest;
import com.mtaretail.core.web.dto.LoginResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final AuditService auditService;

    public AuthController(AppUserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          JwtTokenProvider tokenProvider,
                          AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.auditService = auditService;
    }

    /**
     * Exchanges credentials for a JWT.
     *
     * <p>Failed attempts are audited but answered with a generic message so the
     * endpoint cannot be used to enumerate valid usernames.
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        AppUser user = userRepository.findByUsername(request.username()).orElse(null);

        if (user == null || !user.isEnabled()
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            auditService.record(request.username(), null, "LOGIN_FAILED", "User",
                    request.username(), "Invalid credentials");
            return ResponseEntity.status(401).build();
        }

        String token = tokenProvider.issue(
                user.getUsername(), user.getRole().name(), user.getOutletId());

        auditService.record(user.getUsername(), user.getRole().name(), "LOGIN_OK", "User",
                user.getUsername(), "JWT issued");

        return ResponseEntity.ok(LoginResponse.bearer(
                token, tokenProvider.getTtlSeconds(), user.getUsername(),
                user.getRole().name(), user.getOutletId()));
    }
}