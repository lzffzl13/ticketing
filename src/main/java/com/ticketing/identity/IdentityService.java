package com.ticketing.identity;

import com.ticketing.common.web.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

import static com.ticketing.identity.IdentityModels.*;

@Service
public class IdentityService {
    private static final Duration TOKEN_LIFETIME = Duration.ofHours(2);
    private final IdentityMapper mapper;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public IdentityService(IdentityMapper mapper, PasswordEncoder passwords, Clock clock) {
        this.mapper = mapper;
        this.passwords = passwords;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LoginResult login(IdentityRequests.Login request) {
        var account = mapper.accountByLogin(request.loginName().toLowerCase(Locale.ROOT));
        if (account == null || !account.enabled() || !validPasswordLength(request.password())
                || !passwords.matches(request.password(), account.passwordHash())) {
            throw new BusinessException("UNAUTHORIZED", "账号或密码错误", HttpStatus.UNAUTHORIZED);
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var expiresAt = createdAt.plus(TOKEN_LIFETIME);
        if (mapper.insertToken(digest(token), account.id(), createdAt, expiresAt) != 1) {
            throw new IllegalStateException("Login token was not persisted");
        }
        return new LoginResult(token, expiresAt, account.authenticated());
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public AuthenticatedAccount authenticate(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) { throw invalidToken(); }
        var account = mapper.tokenAccount(digest(token));
        if (account == null || !account.enabled() || !clock.instant().isBefore(account.expiresAt())) {
            throw invalidToken();
        }
        return account.authenticated();
    }

    static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    static boolean validPasswordLength(String password) {
        return password != null && !password.isBlank()
                && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    private BadCredentialsException invalidToken() { return new BadCredentialsException("Invalid bearer token"); }
}
