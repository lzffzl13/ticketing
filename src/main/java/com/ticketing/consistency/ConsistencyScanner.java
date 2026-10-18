package com.ticketing.consistency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Cursor order is disposable; a restart revisits persistent local facts from the beginning. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class ConsistencyScanner {
    private static final Logger log = LoggerFactory.getLogger(ConsistencyScanner.class);
    private final ConsistencyService consistency;
    private final int batchSize;
    private long afterId;
    private Long upperBound;

    public ConsistencyScanner(ConsistencyService consistency,
                              @Value("${ticketing.consistency.batch-size:10}") int batchSize) {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("consistency.batch-size must be between 1 and 100");
        }
        this.consistency = consistency; this.batchSize = batchSize;
    }

    public synchronized int scanOnce() {
        if (upperBound == null) { upperBound = consistency.upperBound(); }
        // Query failures leave both the cursor and traversal bound unchanged for the next scheduled attempt.
        var sessions = consistency.sessions(afterId, upperBound, batchSize);
        for (var sessionId : sessions) {
            try {
                var report = consistency.inspect(sessionId);
                for (var difference : report.differences()) {
                    log.warn("consistency_difference sessionId={} checkedAt={} rule={} entityType={} entityId={} severity={} observed={} recommendation={}",
                            sessionId, report.checkedAt(), difference.rule(), difference.entityType(), difference.entityId(),
                            difference.severity(), difference.observed(), difference.recommendation());
                }
                var recovery = report.recovery();
                if (recovery.manualOrders() > 0 || recovery.manualRefunds() > 0) {
                    log.warn("consistency_manual_recovery sessionId={} manualOrders={} manualRefunds={}",
                            sessionId, recovery.manualOrders(), recovery.manualRefunds());
                }
            } catch (RuntimeException failure) {
                log.warn("consistency_session_failed sessionId={} errorType={}", sessionId, failure.getClass().getSimpleName());
            }
        }
        if (sessions.isEmpty() || sessions.getLast() >= upperBound) { afterId = 0; upperBound = null; }
        else { afterId = sessions.getLast(); }
        return sessions.size();
    }
}
