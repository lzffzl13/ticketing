package com.ticketing.checkin;

import java.time.Instant;
import java.util.List;

public final class CheckinModels {
    private CheckinModels() { }

    public enum Result { SUCCESS, ALREADY_CHECKED_IN, REFUNDING, REFUNDED, INVALID_CREDENTIAL,
        WRONG_SESSION, TOO_EARLY, TOO_LATE, SESSION_NOT_FOUND, INCONSISTENT_STATE }

    public record Attempt(Long id, String requestId, long checkerId, long submittedSessionId,
                          Long sessionId, Long ticketId, String credentialDigest, Instant attemptedAt,
                          Result result) { }

    public record Page(List<Attempt> records, Long nextAfterId) {
        public Page { records = List.copyOf(records); }
    }
}
