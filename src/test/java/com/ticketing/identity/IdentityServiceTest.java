package com.ticketing.identity;

import com.ticketing.common.web.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import static com.ticketing.identity.IdentityModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IdentityServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-08T09:00:00.123456789Z");
    private static final String PASSWORD = "identity-test-password";
    private IdentityMapper mapper;
    private IdentityService identities;
    private BCryptPasswordEncoder passwords;
    private Account consumer;

    @BeforeEach
    void prepare() {
        mapper = mock(IdentityMapper.class);
        passwords = new BCryptPasswordEncoder(4);
        identities = new IdentityService(mapper, passwords, Clock.fixed(NOW, ZoneOffset.UTC));
        consumer = new Account(1, "consumer1", passwords.encode(PASSWORD), Role.CONSUMER, true, NOW, NOW);
        when(mapper.accountByLogin("consumer1")).thenReturn(consumer);
        when(mapper.insertToken(anyString(), anyLong(), any(), any())).thenReturn(1);
    }

    @Test
    void loginNormalizesAccountAndPersistsOnlyDigestWithTwoHourMicrosecondDeadline() {
        var result = identities.login(new IdentityRequests.Login("CONSUMER1", PASSWORD));
        assertEquals(new AuthenticatedAccount(1, "consumer1", Role.CONSUMER), result.account());
        assertEquals(32, Base64.getUrlDecoder().decode(result.token()).length);
        assertFalse(result.token().contains("="));
        var createdAt = NOW.truncatedTo(ChronoUnit.MICROS);
        assertEquals(createdAt.plusSeconds(7200), result.expiresAt());
        var digest = ArgumentCaptor.forClass(String.class);
        verify(mapper).insertToken(digest.capture(), eq(1L), eq(createdAt), eq(result.expiresAt()));
        assertEquals(IdentityService.digest(result.token()), digest.getValue());
        assertTrue(digest.getValue().matches("[0-9a-f]{64}"));
        assertNotEquals(result.token(), digest.getValue());
    }

    @Test
    void eachSuccessfulLoginCreatesIndependentRandomToken() {
        assertNotEquals(identities.login(new IdentityRequests.Login("consumer1", PASSWORD)).token(),
                identities.login(new IdentityRequests.Login("consumer1", PASSWORD)).token());
        verify(mapper, times(2)).insertToken(anyString(), eq(1L), any(), any());
    }

    @Test
    void unknownAccountWrongPasswordDisabledAccountAndOversizeUtf8PasswordNeverCreateToken() {
        assertUnauthorized("unknown", PASSWORD);
        assertUnauthorized("consumer1", "wrong-password");
        assertUnauthorized("consumer1", "密".repeat(25)); // 25 characters exceed BCrypt's 72-byte limit.
        when(mapper.accountByLogin("consumer1")).thenReturn(new Account(1, "consumer1", consumer.passwordHash(),
                Role.CONSUMER, false, NOW, NOW));
        assertUnauthorized("consumer1", PASSWORD);
        verify(mapper, never()).insertToken(anyString(), anyLong(), any(), any());
    }

    @Test
    void tokenAuthenticationReadsCurrentRoleAndEnablement() {
        String token = "A".repeat(43);
        when(mapper.tokenAccount(IdentityService.digest(token))).thenReturn(
                new TokenAccount(8, "operator1", Role.OPERATOR, true, NOW.plusSeconds(1)));
        assertEquals(new AuthenticatedAccount(8, "operator1", Role.OPERATOR), identities.authenticate(token));
    }

    @Test
    void exactExpiryAndUnknownOrDisabledTokensAreRejected() {
        String token = "A".repeat(43);
        assertThrows(BadCredentialsException.class, () -> identities.authenticate(token));
        when(mapper.tokenAccount(IdentityService.digest(token))).thenReturn(
                new TokenAccount(1, "consumer1", Role.CONSUMER, true, NOW));
        assertThrows(BadCredentialsException.class, () -> identities.authenticate(token));
        when(mapper.tokenAccount(IdentityService.digest(token))).thenReturn(
                new TokenAccount(1, "consumer1", Role.CONSUMER, false, NOW.plusSeconds(7200)));
        assertThrows(BadCredentialsException.class, () -> identities.authenticate(token));
    }

    @Test
    void malformedTokensAreRejectedBeforeDatabaseAccess() {
        for (String token : new String[] { "", "short", "A".repeat(44), "A".repeat(42) + "=", " ".repeat(43) }) {
            assertThrows(BadCredentialsException.class, () -> identities.authenticate(token));
        }
        assertThrows(BadCredentialsException.class, () -> identities.authenticate(null));
        verify(mapper, never()).tokenAccount(anyString());
    }

    @Test
    void failedTokenInsertCannotReturnSuccessfulLogin() {
        when(mapper.insertToken(anyString(), anyLong(), any(), any())).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> identities.login(new IdentityRequests.Login("consumer1", PASSWORD)));
    }

    private void assertUnauthorized(String loginName, String password) {
        var exception = assertThrows(BusinessException.class,
                () -> identities.login(new IdentityRequests.Login(loginName, password)));
        assertEquals(401, exception.getStatus().value());
        assertEquals("UNAUTHORIZED", exception.getCode());
    }
}
