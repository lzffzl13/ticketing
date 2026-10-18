package com.ticketing.consistency;

import java.time.Instant;
import java.util.List;

/** Reports expose business identities and observations, never credentials or recovery ownership tokens. */
public final class ConsistencyModels {
    private ConsistencyModels() { }

    public enum Severity { ERROR }

    public record Difference(String rule, String entityType, long entityId, Severity severity,
                             String observed, String recommendation) { }

    public record Counts(int orders, int seats, int tickets, int refunds, int successfulCheckins) { }

    /** Normal unfinished channel work is recovery information, not a local cross-table violation. */
    public record RecoverySummary(long pendingOrders, long unresolvedPayments, long manualOrders,
                                  long unresolvedRefunds, long manualRefunds) { }

    public record SessionReport(long sessionId, Instant checkedAt, boolean consistent, Counts counts,
                                RecoverySummary recovery, List<Difference> differences) {
        public SessionReport { differences = List.copyOf(differences); }
    }
}
