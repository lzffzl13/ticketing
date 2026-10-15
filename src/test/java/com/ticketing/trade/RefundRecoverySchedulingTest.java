package com.ticketing.trade;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefundRecoverySchedulingTest {
    @Test
    void enabledByDefaultSchedulingRunsAgainAfterTheFirstBatchFails() throws Exception {
        var scanner = mock(RefundRecoveryScanner.class);
        var calls = new AtomicInteger();
        var retried = new CountDownLatch(1);
        when(scanner.scanOnce()).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) { throw new IllegalStateException("test first refund batch fails"); }
            retried.countDown();
            return 0;
        });

        try (var context = schedulingContext(scanner, Map.of("ticketing.refund-recovery.fixed-delay-ms", "20"))) {
            assertEquals(1, context.getBeansOfType(RefundRecoverySchedulingConfiguration.ScheduledRecovery.class).size());
            assertEquals(1, context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                    .mapToInt(holder -> holder.getScheduledTasks().size()).sum());
            assertTrue(retried.await(10, TimeUnit.SECONDS), "a failed batch must not disable later scheduled scans");
            assertTrue(calls.get() >= 2);
        }
    }

    @Test
    void explicitDisableRegistersNoRefundTaskAndMakesNoBackgroundCalls() {
        var scanner = mock(RefundRecoveryScanner.class);
        try (var context = schedulingContext(scanner, Map.of(
                "ticketing.refund-recovery.enabled", "false",
                "ticketing.refund-recovery.fixed-delay-ms", "20"))) {
            assertTrue(context.getBeansOfType(RefundRecoverySchedulingConfiguration.ScheduledRecovery.class).isEmpty());
            assertTrue(context.getBeansOfType(ScheduledTaskHolder.class).isEmpty());
            verifyNoInteractions(scanner);
        }
    }

    private AnnotationConfigApplicationContext schedulingContext(RefundRecoveryScanner scanner,
                                                                 Map<String, Object> properties) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("refund-recovery-test", properties));
        context.registerBean(RefundRecoveryScanner.class, () -> scanner);
        context.register(RefundRecoverySchedulingConfiguration.class);
        context.refresh();
        return context;
    }
}
