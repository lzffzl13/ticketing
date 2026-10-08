package com.ticketing.identity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.security.Principal;
import java.time.Instant;

public final class IdentityModels {
    private IdentityModels() { }

    public enum Role { CONSUMER, OPERATOR, CHECKER }

    public record AuthenticatedAccount(long id, String loginName, Role role) implements Principal {
        @JsonIgnore @Override public String getName() { return loginName; }
    }

    public record LoginResult(String token, Instant expiresAt, AuthenticatedAccount account) { }

    record Account(long id, String loginName, String passwordHash, Role role, boolean enabled,
                   Instant createdAt, Instant updatedAt) {
        AuthenticatedAccount authenticated() { return new AuthenticatedAccount(id, loginName, role); }
    }

    record TokenAccount(long id, String loginName, Role role, boolean enabled, Instant expiresAt) {
        AuthenticatedAccount authenticated() { return new AuthenticatedAccount(id, loginName, role); }
    }
}
