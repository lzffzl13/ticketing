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

class PaymentRecoverySchedulingTest {
    @Test
    void defaultEnabledSchedulingRunsWithoutManualTicksAndRetriesAfterABatchFailure() throws Exception {
        var scanner = mock(PaymentRecoveryScanner.class);
        var calls = new AtomicInteger();
        var retried = new CountDownLatch(1);
        when(scanner.scanOnce()).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("test first scheduled query unavailable");
            }
            retried.countDown();
            return 0;
        });

        try (var context = schedulingContext(scanner, Map.of("ticketing.payment-recovery.fixed-delay-ms", "20"))) {
            assertEquals(1, context.getBeansOfType(PaymentRecoverySchedulingConfiguration.ScheduledRecovery.class).size());
            assertEquals(1, context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                    .mapToInt(holder -> holder.getScheduledTasks().size()).sum());
            assertTrue(retried.await(10, TimeUnit.SECONDS), "Spring must trigger a new scan after the failed batch");
            assertTrue(calls.get() >= 2);
            verify(scanner, atLeast(2)).scanOnce();
        }
    }

    @Test
    void disabledConfigurationRegistersNeitherTheBackgroundBeanNorScheduledTasks() {
        var scanner = mock(PaymentRecoveryScanner.class);
        try (var context = schedulingContext(scanner, Map.of(
                "ticketing.payment-recovery.enabled", "false",
                "ticketing.payment-recovery.fixed-delay-ms", "20"))) {
            assertTrue(context.getBeansOfType(PaymentRecoverySchedulingConfiguration.ScheduledRecovery.class).isEmpty());
            assertTrue(context.getBeansOfType(ScheduledTaskHolder.class).isEmpty());
            verifyNoInteractions(scanner);
        }
    }

    private AnnotationConfigApplicationContext schedulingContext(PaymentRecoveryScanner scanner, Map<String, Object> properties) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("recovery-test", properties));
        context.registerBean(PaymentRecoveryScanner.class, () -> scanner);
        context.register(PaymentRecoverySchedulingConfiguration.class);
        context.refresh();
        return context;
    }
}
