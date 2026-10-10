package com.ticketing.trade;

import java.time.Duration;
import java.time.Instant;

final class RecoveryPolicy {
    static final Duration LEASE = Duration.ofSeconds(60);
    private static final long[] BACKOFF_SECONDS = {5, 15, 30, 60, 120};
    private RecoveryPolicy() { }
    static Duration backoff(int failures) {
        return Duration.ofSeconds(BACKOFF_SECONDS[Math.min(Math.max(failures, 1), 5) - 1]);
    }
    static boolean requiresManual(int failures, Instant first, Instant now) {
        return failures >= 6 || (first != null && !now.isBefore(first.plusSeconds(600)));
    }
}
