package com.ticketing.consistency;

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

class ConsistencySchedulingTest {
    @Test
    void defaultEnabledSchedulingContinuesAfterTheFirstFailure() throws Exception {
        var scanner = mock(ConsistencyScanner.class);
        var calls = new AtomicInteger();
        var retried = new CountDownLatch(1);
        when(scanner.scanOnce()).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) { throw new IllegalStateException("test scan unavailable"); }
            retried.countDown(); return 0;
        });
        try (var context = context(scanner, Map.of("ticketing.consistency.fixed-delay-ms", "20"))) {
            assertEquals(1, context.getBeansOfType(ConsistencySchedulingConfiguration.ScheduledConsistency.class).size());
            assertTrue(retried.await(10, TimeUnit.SECONDS));
            verify(scanner, atLeast(2)).scanOnce();
        }
    }

    @Test
    void disabledConfigurationCreatesNoBackgroundTask() {
        var scanner = mock(ConsistencyScanner.class);
        try (var context = context(scanner, Map.of("ticketing.consistency.enabled", "false"))) {
            assertTrue(context.getBeansOfType(ConsistencySchedulingConfiguration.ScheduledConsistency.class).isEmpty());
            assertTrue(context.getBeansOfType(ScheduledTaskHolder.class).isEmpty());
            verifyNoInteractions(scanner);
        }
    }

    private AnnotationConfigApplicationContext context(ConsistencyScanner scanner, Map<String, Object> properties) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("consistency-test", properties));
        context.registerBean(ConsistencyScanner.class, () -> scanner);
        context.register(ConsistencySchedulingConfiguration.class);
        context.refresh();
        return context;
    }
}
