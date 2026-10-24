package com.mtaretail.core.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mtaretail.core.AbstractMySqlIT;
import com.mtaretail.core.domain.Account;
import com.mtaretail.core.domain.AppUser;
import com.mtaretail.core.domain.Outlet;
import com.mtaretail.core.domain.Role;
import com.mtaretail.core.repository.AccountRepository;
import com.mtaretail.core.repository.AppUserRepository;
import com.mtaretail.core.repository.OutletRepository;
import com.mtaretail.core.repository.TxnTransactionRepository;
import com.mtaretail.core.service.TransactionService;
import com.mtaretail.core.web.dto.CreateAccountRequest;
import com.mtaretail.core.web.dto.PostTransactionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OutletAuthorizationIT extends AbstractMySqlIT {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OutletRepository outlets;
    @Autowired AppUserRepository users;
    @Autowired AccountRepository accounts;
    @Autowired TxnTransactionRepository transactions;

    private String a;
    private String b;
    private String accountB;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        a = "POS-A-" + suffix;
        b = "POS-B-" + suffix;
        Outlet outletA = outlets.save(new Outlet(a, "Outlet A", "HCM"));
        Outlet outletB = outlets.save(new Outlet(b, "Outlet B", "HN"));
        users.save(new AppUser("staff-a-" + suffix, "unused", "Staff A", Role.POS_STAFF, outletA.getId()));
        users.save(new AppUser("staff-b-" + suffix, "unused", "Staff B", Role.POS_STAFF, outletB.getId()));
        accountB = "AUTH-" + suffix;
    }

    @Test
    void staffCannotOpenOrAttributeOpeningMovementToAnotherOutlet() throws Exception {
        String suffix = a.substring(a.length() - 8);
        String accountNo = "DENY-" + suffix;
        // A new account's owner and opening transaction must come from the actor, not an arbitrary first outlet.
        var response = mvc.perform(post("/api/v1/accounts")
                        .with(user("staff-b-" + suffix).roles("POS_STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CreateAccountRequest(accountNo, "Customer B",
                                "123" + suffix.replaceAll("[^0-9]", "1") + "456", 100L))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        var opened = json.readTree(response.getContentAsString());
        assertEquals(100L, opened.get("balance").asLong());
        assertEquals(outlets.findByCode(b).orElseThrow().getId(),
                transactions.findByIdempotencyKey("opening-" + accountNo).orElseThrow().getOutletId());
    }

    @Test
    void staffCannotReadForeignAccountOrTransactionOrPostOrReplay() throws Exception {
        String suffix = a.substring(a.length() - 8);
        String userB = "staff-b-" + suffix;
        String userA = "staff-a-" + suffix;
        mvc.perform(post("/api/v1/accounts").with(user(userB).roles("POS_STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CreateAccountRequest(accountB, "Customer B",
                                "987654321" + suffix.replaceAll("[^0-9]", "1").substring(0, 2), 100L))))
                .andExpect(status().isCreated());
        String key = "AUTH-KEY-" + suffix;
        var movement = new PostTransactionRequest("CASH_IN", accountB, b, 10L, 0L, key);
        String txnJson = mvc.perform(post("/api/v1/transactions").with(user(userB).roles("POS_STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(movement)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String ref = json.readTree(txnJson).get("txnRef").asText();
        long before = accounts.findByAccountNo(accountB).orElseThrow().getBalance();

        mvc.perform(get("/api/v1/accounts/{no}", accountB).with(user(userA).roles("POS_STAFF")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/transactions/{ref}", ref).with(user(userA).roles("POS_STAFF")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/transactions").with(user(userA).roles("POS_STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(movement)))
                .andExpect(status().isForbidden());
        var foreignNewKey = new PostTransactionRequest("CASH_IN", accountB, b, 10L, 0L,
                "AUTH-FOREIGN-" + suffix);
        mvc.perform(post("/api/v1/transactions").with(user(userA).roles("POS_STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(foreignNewKey)))
                .andExpect(status().isForbidden());
        var wrongOutlet = new PostTransactionRequest("CASH_IN", accountB, a, 10L, 0L,
                "AUTH-OTHER-" + suffix);
        mvc.perform(post("/api/v1/transactions").with(user(userA).roles("POS_STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(wrongOutlet)))
                .andExpect(status().isForbidden());
        assertEquals(before, accounts.findByAccountNo(accountB).orElseThrow().getBalance());
        assertTrue(transactions.findByIdempotencyKey(wrongOutlet.idempotencyKey()).isEmpty());
        assertTrue(transactions.findByIdempotencyKey(foreignNewKey.idempotencyKey()).isEmpty());
    }

    @Test
    void zeroOpeningBalanceStillRecordsOwnerAndDeniesOtherOutlet() throws Exception {
        String suffix = a.substring(a.length() - 8);
        String accountNo = "ZERO-" + suffix;
        mvc.perform(post("/api/v1/accounts").with(user("staff-b-" + suffix).roles("POS_STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CreateAccountRequest(accountNo, "Zero Customer",
                                "555555555" + suffix.replaceAll("[^0-9]", "1").substring(0, 2), 0L))))
                .andExpect(status().isCreated());
        assertEquals(outlets.findByCode(b).orElseThrow().getId(),
                accounts.findByAccountNo(accountNo).orElseThrow().getOutletId());
        assertTrue(transactions.findByIdempotencyKey("opening-" + accountNo).isEmpty());
        mvc.perform(get("/api/v1/accounts/{no}", accountNo)
                        .with(user("staff-a-" + suffix).roles("POS_STAFF")))
                .andExpect(status().isForbidden());
    }

    @Test
    void unresolvedLegacyAccountIsFailClosedForStaffButVisibleToOperations() throws Exception {
        String suffix = a.substring(a.length() - 8);
        String legacy = "LEGACY-" + suffix;
        accounts.save(new Account(legacy, "Legacy Customer",
                TransactionService.hashIdNumber("LEGACY-ID-" + suffix)));

        mvc.perform(get("/api/v1/accounts/{no}", legacy)
                        .with(user("staff-a-" + suffix).roles("POS_STAFF")))
                .andExpect(status().isForbidden());

        users.save(new AppUser("ops-" + suffix, "unused", "Operations", Role.OPS, null));
        mvc.perform(get("/api/v1/accounts/{no}", legacy)
                        .with(user("ops-" + suffix).roles("OPS")))
                .andExpect(status().isOk());
        users.save(new AppUser("admin-" + suffix, "unused", "Administrator", Role.ADMIN, null));
        mvc.perform(get("/api/v1/accounts/{no}", legacy)
                        .with(user("admin-" + suffix).roles("ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/accounts").with(user("ops-" + suffix).roles("OPS"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CreateAccountRequest("OPS-" + suffix,
                                "No Posting", "111111111", 0L))))
                .andExpect(status().isForbidden());
    }
}
