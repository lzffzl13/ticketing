package com.ticketing.trade;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="ticketing.refund-recovery",name="enabled",havingValue="true",matchIfMissing=true)
@EnableScheduling
public class RefundRecoverySchedulingConfiguration {
    @Bean ScheduledRecovery scheduledRefundRecovery(RefundRecoveryScanner scanner) { return new ScheduledRecovery(scanner); }
    static class ScheduledRecovery {
        private static final Logger log=LoggerFactory.getLogger(ScheduledRecovery.class);
        private final RefundRecoveryScanner scanner;
        ScheduledRecovery(RefundRecoveryScanner scanner) { this.scanner=scanner; }
        @Scheduled(fixedDelayString="${ticketing.refund-recovery.fixed-delay-ms:1000}")
        public void tick() {
            try { scanner.scanOnce(); }
            catch(RuntimeException failure) { log.warn("refund_scan_failed errorType={}",failure.getClass().getSimpleName()); }
        }
    }
}
