package com.mtaretail.core.config;

import com.mtaretail.core.domain.Account;
import com.mtaretail.core.domain.AppUser;
import com.mtaretail.core.domain.Outlet;
import com.mtaretail.core.domain.Role;
import com.mtaretail.core.repository.AccountRepository;
import com.mtaretail.core.repository.AppUserRepository;
import com.mtaretail.core.repository.OutletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds one outlet and a starter user so the API can be exercised locally.
 *
 * <p>Disabled by default ({@code app.seed.enabled}) - never enable in an
 * environment that holds real customer data.
 */
@Configuration
public class DataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    @Bean
    ApplicationRunner seedRunner(AppUserRepository userRepository,
                                 OutletRepository outletRepository,
                                 AccountRepository accountRepository,
                                 PasswordEncoder passwordEncoder,
                                 @Value("${app.seed.enabled:false}") boolean enabled,
                                 @Value("${app.seed.password:ChangeMe123!}") String seedPassword) {
        return args -> {
            if (!enabled) {
                log.info("Seed disabled; set app.seed.enabled=true to populate demo data");
                return;
            }
            seed(userRepository, outletRepository, accountRepository, passwordEncoder, seedPassword);
        };
    }

    @Transactional
    void seed(AppUserRepository userRepository,
              OutletRepository outletRepository,
              AccountRepository accountRepository,
              PasswordEncoder passwordEncoder,
              String seedPassword) {

        Outlet outlet = outletRepository.findByCode("OUTLET-HCM-01")
                .orElseGet(() -> outletRepository.save(
                        new Outlet("OUTLET-HCM-01", "Demo Outlet - District 1", "HCM")));

        if (userRepository.findByUsername("admin").isEmpty()) {
            userRepository.save(new AppUser(
                    "admin", passwordEncoder.encode(seedPassword), "Seed Admin",
                    Role.ADMIN, outlet.getId()));
            log.info("Seeded demo admin user");
        }
        if (userRepository.findByUsername("ops").isEmpty()) {
            userRepository.save(new AppUser(
                    "ops", passwordEncoder.encode(seedPassword), "Seed Ops",
                    Role.OPS, null));
        }
        if (userRepository.findByUsername("pos01").isEmpty()) {
            userRepository.save(new AppUser(
                    "pos01", passwordEncoder.encode(seedPassword), "Seed Cashier",
                    Role.POS_STAFF, outlet.getId()));
        }

        if (accountRepository.findByAccountNo("ACC000001").isEmpty()) {
            accountRepository.save(new Account("ACC000001", "Demo Customer", sha256("000000001")));
            log.info("Seeded account ACC000001");
        }
    }

    private static String sha256(String raw) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
