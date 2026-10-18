package com.ticketing.consistency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ticketing.consistency", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class ConsistencySchedulingConfiguration {
    @Bean
    ScheduledConsistency scheduledConsistency(ConsistencyScanner scanner) { return new ScheduledConsistency(scanner); }

    static class ScheduledConsistency {
        private static final Logger log = LoggerFactory.getLogger(ScheduledConsistency.class);
        private final ConsistencyScanner scanner;
        ScheduledConsistency(ConsistencyScanner scanner) { this.scanner = scanner; }

        @Scheduled(fixedDelayString = "${ticketing.consistency.fixed-delay-ms:60000}")
        public void tick() {
            try { scanner.scanOnce(); }
            catch (RuntimeException failure) {
                log.warn("consistency_scan_failed errorType={}", failure.getClass().getSimpleName());
            }
        }
    }
}
