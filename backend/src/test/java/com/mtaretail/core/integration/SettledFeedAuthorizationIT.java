package com.mtaretail.core.integration;

import com.mtaretail.core.AbstractMySqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SettledFeedAuthorizationIT extends AbstractMySqlIT {

    @Autowired MockMvc mvc;

    @Test
    void posStaffCannotReadTheFullSettledLedgerFeed() throws Exception {
        mvc.perform(get("/api/v1/transactions/settled")
                        .param("from", "2026-10-01T00:00:00Z")
                        .param("to", "2026-10-02T00:00:00Z")
                        .with(user("pos").roles("POS_STAFF")))
                .andExpect(status().isForbidden());
    }

    @Test
    void opsCanReadTheSettledLedgerFeed() throws Exception {
        mvc.perform(get("/api/v1/transactions/settled")
                        .param("from", "2026-10-01T00:00:00Z")
                        .param("to", "2026-10-02T00:00:00Z")
                        .with(user("ops").roles("OPS")))
                .andExpect(status().isOk());
    }
}
