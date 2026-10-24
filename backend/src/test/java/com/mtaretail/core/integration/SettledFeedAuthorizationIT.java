package com.mtaretail.core.integration;

import com.mtaretail.core.AbstractMySqlIT;
import com.mtaretail.core.domain.AppUser;
import com.mtaretail.core.domain.Outlet;
import com.mtaretail.core.domain.Role;
import com.mtaretail.core.repository.AppUserRepository;
import com.mtaretail.core.repository.OutletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SettledFeedAuthorizationIT extends AbstractMySqlIT {

    @Autowired MockMvc mvc;
    @Autowired OutletRepository outlets;
    @Autowired AppUserRepository users;

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

    @Test
    void posStaffCanReadTheirOwnOutletSettledScopedFeed() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String codeA = "OUT-A-" + suffix;
        Outlet outletA = outlets.save(new Outlet(codeA, "Outlet A", "HN"));
        String usernameA = "staff-scoped-" + suffix;
        users.save(new AppUser(usernameA, "unused", "Staff Scoped", Role.POS_STAFF, outletA.getId()));

        mvc.perform(get("/api/v1/transactions/settled-scoped")
                        .param("outletCode", codeA)
                        .param("from", "2026-10-01T00:00:00Z")
                        .param("to", "2026-10-02T00:00:00Z")
                        .with(user(usernameA).roles("POS_STAFF")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Service-SHA", "433447f"))
                .andExpect(header().string("X-Record-Version", "1"));
    }

    @Test
    void posStaffCannotReadAnotherOutletSettledScopedFeed() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String codeA = "OUT-A-" + suffix;
        String codeB = "OUT-B-" + suffix;
        Outlet outletA = outlets.save(new Outlet(codeA, "Outlet A", "HN"));
        outlets.save(new Outlet(codeB, "Outlet B", "SG"));
        String usernameA = "staff-cross-" + suffix;
        users.save(new AppUser(usernameA, "unused", "Staff Cross", Role.POS_STAFF, outletA.getId()));

        mvc.perform(get("/api/v1/transactions/settled-scoped")
                        .param("outletCode", codeB)
                        .param("from", "2026-10-01T00:00:00Z")
                        .param("to", "2026-10-02T00:00:00Z")
                        .with(user(usernameA).roles("POS_STAFF")))
                .andExpect(status().isForbidden());
    }
}
