package com.ticketing.trade;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ticketing.payment-recovery", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class PaymentRecoverySchedulingConfiguration {
    @Bean
    ScheduledRecovery scheduledPaymentRecovery(PaymentRecoveryScanner scanner) {
        return new ScheduledRecovery(scanner);
    }

    static class ScheduledRecovery {
        private static final Logger log = LoggerFactory.getLogger(ScheduledRecovery.class);
        private final PaymentRecoveryScanner scanner;

        ScheduledRecovery(PaymentRecoveryScanner scanner) { this.scanner = scanner; }

        @Scheduled(fixedDelayString = "${ticketing.payment-recovery.fixed-delay-ms:1000}")
        public void tick() {
            try { scanner.scanOnce(); }
            catch (RuntimeException failure) {
                log.warn("payment_recovery_scan_failed errorType={}", failure.getClass().getSimpleName());
            }
        }
    }
}
