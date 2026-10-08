package com.ticketing.identity;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static com.ticketing.identity.IdentityModels.Role;

@Service
public class DemoAccountSeeder {
    private record SeedAccount(String loginName, Role role) { }
    private static final List<SeedAccount> ACCOUNTS = List.of(new SeedAccount("consumer1", Role.CONSUMER),
            new SeedAccount("consumer2", Role.CONSUMER), new SeedAccount("operator1", Role.OPERATOR),
            new SeedAccount("checker1", Role.CHECKER));

    private final IdentityMapper mapper;
    private final PasswordEncoder passwords;
    private final Clock clock;

    public DemoAccountSeeder(IdentityMapper mapper, PasswordEncoder passwords, Clock clock) {
        this.mapper = mapper;
        this.passwords = passwords;
        this.clock = clock;
    }

    /** Reuse identical fixed accounts; a mismatch aborts and rolls back the whole seed. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void seed(String password) {
        if (!IdentityService.validPasswordLength(password)) {
            throw new IllegalArgumentException("Demo password must be nonblank and at most 72 UTF-8 bytes");
        }
        var at = clock.instant().truncatedTo(ChronoUnit.MICROS);
        for (var seed : ACCOUNTS) {
            mapper.ensureAccount(seed.loginName(), passwords.encode(password), seed.role(), at);
            var account = mapper.accountByLogin(seed.loginName());
            if (account == null || account.role() != seed.role() || !account.enabled()
                    || !passwords.matches(password, account.passwordHash())) {
                throw new IllegalStateException("Existing demo account differs from seed: " + seed.loginName());
            }
        }
    }
}
