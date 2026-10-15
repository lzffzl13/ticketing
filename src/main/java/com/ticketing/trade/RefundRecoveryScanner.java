package com.ticketing.trade;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Clock;
import java.time.temporal.ChronoUnit;

@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class RefundRecoveryScanner {
    private static final Logger log=LoggerFactory.getLogger(RefundRecoveryScanner.class);
    private final RefundQueryService queries;
    private final RefundService refunds;
    private final Clock clock;
    private final int batchSize;
    private long afterId;
    private Long upperBound;
    public RefundRecoveryScanner(RefundQueryService queries,RefundService refunds,Clock clock,
            @Value("${ticketing.refund-recovery.batch-size:50}") int batchSize) {
        if(batchSize<1 || batchSize>500) { throw new IllegalArgumentException("refund-recovery.batch-size must be between 1 and 500"); }
        this.queries=queries; this.refunds=refunds; this.clock=clock; this.batchSize=batchSize;
    }
    public synchronized int scanOnce() {
        if(upperBound==null) { upperBound=queries.upperBound(); }
        var candidates=queries.due(clock.instant().truncatedTo(ChronoUnit.MICROS),afterId,upperBound,batchSize);
        for(var id:candidates) {
            try { refunds.recoverIfDue(id); }
            catch(RuntimeException failure) { log.warn("refund_scan_item_failed refundId={} errorType={}",id,failure.getClass().getSimpleName()); }
        }
        if(candidates.isEmpty() || candidates.getLast()>=upperBound) { afterId=0; upperBound=null; }
        else { afterId=candidates.getLast(); }
        return candidates.size();
    }
}
