package com.mtaretail.core.security;

import com.mtaretail.core.domain.AppUser;
import com.mtaretail.core.repository.AppUserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Reads {@code Authorization: Bearer <jwt>} and populates the security context.
 *
 * <p>An absent header is left anonymous and lets the authorization rules
 * decide. A present-but-invalid header is also left anonymous - the request
 * then fails with 401 at the authorization stage rather than 500 here.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;
    private final AppUserRepository users;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider, AppUserRepository users) {
        this.tokenProvider = tokenProvider;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            Claims claims = tokenProvider.parse(token);
            if (claims != null) {
                String username = claims.getSubject();
                AppUser user = users.findByUsername(username).orElse(null);
                if (user != null && user.isEnabled()) {
                    // JWT proves identity; current DB role decides authorization.
                    var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
                    var auth = new UsernamePasswordAuthenticationToken(username, null, authorities);
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } else {
                log.debug("Invalid bearer token presented to {}", request.getRequestURI());
            }
        }
        chain.doFilter(request, response);
    }
}
