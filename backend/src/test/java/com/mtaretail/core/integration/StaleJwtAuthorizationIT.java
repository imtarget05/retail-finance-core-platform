package com.mtaretail.core.integration;

import com.mtaretail.core.AbstractMySqlIT;
import com.mtaretail.core.domain.AppUser;
import com.mtaretail.core.domain.Role;
import com.mtaretail.core.repository.AppUserRepository;
import com.mtaretail.core.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class StaleJwtAuthorizationIT extends AbstractMySqlIT {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;

    @Test
    void demotedOrDisabledOpsCannotUsePreviouslyIssuedTokenForLedgerOrAudit() throws Exception {
        String username = "ops-stale-" + UUID.randomUUID().toString().substring(0, 8);
        users.save(new AppUser(username, "unused", "Ops", Role.OPS, null));
        String bearer = "Bearer " + tokens.issue(username, "OPS", null);

        mvc.perform(settled(bearer)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/audit/actor/{actor}", username)
                        .header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isOk());

        jdbc.update("UPDATE app_user SET role='POS_STAFF' WHERE username=?", username);
        mvc.perform(settled(bearer)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit/actor/{actor}", username)
                        .header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isForbidden());

        jdbc.update("UPDATE app_user SET enabled=0 WHERE username=?", username);
        mvc.perform(settled(bearer)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/audit/actor/{actor}", username)
                        .header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isUnauthorized());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder settled(String bearer) {
        return get("/api/v1/transactions/settled")
                .param("from", "2026-10-01T00:00:00Z")
                .param("to", "2026-10-02T00:00:00Z")
                .header(HttpHeaders.AUTHORIZATION, bearer);
    }
}
