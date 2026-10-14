package com.ticketing.trade;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

/** Reads bounded candidate IDs; every order rechecks eligibility in its existing recovery transaction. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class PaymentRecoveryScanner {
    private static final Logger log = LoggerFactory.getLogger(PaymentRecoveryScanner.class);
    private final PaymentQueryService queries;
    private final PaymentService payments;
    private final Clock clock;
    private final int batchSize;
    private long afterId;
    private Long upperBound;

    public PaymentRecoveryScanner(PaymentQueryService queries, PaymentService payments, Clock clock,
                                  @Value("${ticketing.payment-recovery.batch-size:50}") int batchSize) {
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("payment-recovery.batch-size must be between 1 and 500");
        }
        this.queries = queries;
        this.payments = payments;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /** Returns selected count, not successful cancellation/payment count. No transaction spans the batch. */
    public synchronized int scanOnce() {
        if (upperBound == null) { upperBound = queries.recoveryUpperBound(); }
        var candidates = queries.dueRecoveryOrders(clock.instant().truncatedTo(ChronoUnit.MICROS),
                afterId, upperBound, batchSize);
        for (var orderId : candidates) {
            try { payments.recoverIfDue(orderId); }
            catch (RuntimeException failure) {
                // The durable order/lease remains discoverable on the next traversal, even after a DB fault.
                log.warn("payment_recovery_scan_order_failed orderId={} errorType={}",
                        orderId, failure.getClass().getSimpleName());
            }
        }
        if (candidates.isEmpty() || candidates.getLast() >= upperBound) {
            afterId = 0;
            upperBound = null;
        } else { afterId = candidates.getLast(); }
        // A fixed upper bound forces a wrap even when new, higher IDs continuously arrive.
        log.debug("payment_recovery_scan selected={} nextAfterId={}", candidates.size(), afterId);
        return candidates.size();
    }
}
