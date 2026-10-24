package com.mtaretail.core.config;

import com.mtaretail.core.security.JwtAuthenticationFilter;
import com.mtaretail.core.security.RestAuthenticationEntryPoint;
import com.mtaretail.core.security.RestAccessDeniedHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless JWT security for a POS-facing API.
 *
 * <p>Rules, coarsest first:
 * <ul>
 *   <li>login + docs + health are open</li>
 *   <li>audit logs are OPS/ADMIN only (contain sensitive detail)</li>
 *   <li>user management is ADMIN only</li>
 *   <li>outlet/account reads are staff and above</li>
 *   <li>creating money movements needs staff, but POST/PUT/DELETE requires
 *       an authenticated session and is additionally narrowed by
 *       {@code @PreAuthorize} in the controller layer</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new RestAuthenticationEntryPoint())
                        .accessDeniedHandler(new RestAccessDeniedHandler()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/auth/login",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html").permitAll()
                        .requestMatchers("/actuator/prometheus").hasRole("OPS")
                        .requestMatchers("/api/v1/audit/**").hasAnyRole("OPS", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/users/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/users/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/users/**").hasAnyRole("OUTLET_MANAGER", "OPS", "ADMIN")
                        .requestMatchers("/api/v1/outlets/**").hasAnyRole("OUTLET_MANAGER", "OPS", "ADMIN")
                        .requestMatchers("/api/v1/accounts/**").hasAnyRole("POS_STAFF", "OUTLET_MANAGER", "OPS", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/transactions/settled").hasRole("OPS")
                        .requestMatchers(HttpMethod.GET, "/api/v1/transactions/settled-scoped").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/transactions/**")
                            .hasAnyRole("POS_STAFF", "OUTLET_MANAGER")
                        .requestMatchers("/api/v1/transactions/**").authenticated()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
