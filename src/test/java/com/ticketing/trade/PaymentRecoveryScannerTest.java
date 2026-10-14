package com.ticketing.trade;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentRecoveryScannerTest {
    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00.123456Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private PaymentQueryService queries;
    private PaymentService payments;

    @BeforeEach
    void prepare() {
        queries = mock(PaymentQueryService.class);
        payments = mock(PaymentService.class);
    }

    @Test
    void oneOrderFailureDoesNotBlockLaterCandidatesAndSelectedCountIncludesTheFailure() {
        var scanner = new PaymentRecoveryScanner(queries, payments, clock, 3);
        when(queries.recoveryUpperBound()).thenReturn(3L);
        when(queries.dueRecoveryOrders(NOW, 0, 3, 3)).thenReturn(List.of(1L, 2L, 3L));
        when(payments.recoverIfDue(1L)).thenThrow(new IllegalStateException("test order recovery unavailable"));

        assertEquals(3, scanner.scanOnce());

        var processing = inOrder(payments);
        processing.verify(payments).recoverIfDue(1L);
        processing.verify(payments).recoverIfDue(2L);
        processing.verify(payments).recoverIfDue(3L);
    }

    @Test
    void failedCandidateQueryKeepsItsCursorAndTraversalBoundForTheNextAttempt() {
        var scanner = new PaymentRecoveryScanner(queries, payments, clock, 2);
        when(queries.recoveryUpperBound()).thenReturn(6L);
        when(queries.dueRecoveryOrders(NOW, 0, 6, 2)).thenReturn(List.of(1L, 2L));
        when(queries.dueRecoveryOrders(NOW, 2, 6, 2))
                .thenThrow(new IllegalStateException("test candidate database unavailable"))
                .thenReturn(List.of(5L, 6L));

        assertEquals(2, scanner.scanOnce());
        assertThrows(IllegalStateException.class, scanner::scanOnce);
        assertEquals(2, scanner.scanOnce());

        verify(queries, times(1)).recoveryUpperBound();
        verify(queries, times(2)).dueRecoveryOrders(NOW, 2, 6, 2);
        verify(payments, times(1)).recoverIfDue(1L);
        verify(payments, times(1)).recoverIfDue(2L);
        verify(payments).recoverIfDue(5L);
        verify(payments).recoverIfDue(6L);
    }

    @Test
    void boundedBatchesAdvancePastFailedLowIdsAndWrapBeforeIncludingNewHigherIds() {
        var scanner = new PaymentRecoveryScanner(queries, payments, clock, 2);
        when(queries.recoveryUpperBound()).thenReturn(6L, 9L);
        when(queries.dueRecoveryOrders(NOW, 0, 6, 2)).thenReturn(List.of(1L, 2L));
        when(queries.dueRecoveryOrders(NOW, 2, 6, 2)).thenReturn(List.of(4L, 6L));
        when(queries.dueRecoveryOrders(NOW, 0, 9, 2)).thenReturn(List.of(1L, 2L));
        when(payments.recoverIfDue(1L)).thenThrow(new IllegalStateException("test persistent low-id failure"));

        assertEquals(2, scanner.scanOnce());
        assertEquals(2, scanner.scanOnce());
        assertEquals(2, scanner.scanOnce());

        var traversal = inOrder(queries);
        traversal.verify(queries).recoveryUpperBound();
        traversal.verify(queries).dueRecoveryOrders(NOW, 0, 6, 2);
        traversal.verify(queries).dueRecoveryOrders(NOW, 2, 6, 2);
        traversal.verify(queries).recoveryUpperBound();
        traversal.verify(queries).dueRecoveryOrders(NOW, 0, 9, 2);
        verify(payments).recoverIfDue(4L);
        verify(payments).recoverIfDue(6L);
        verify(payments, times(2)).recoverIfDue(1L);
    }

    @Test
    void emptyRemainingBatchStartsANewTraversalSoLaterDueOrdersAreDiscoverable() {
        var scanner = new PaymentRecoveryScanner(queries, payments, clock, 2);
        when(queries.recoveryUpperBound()).thenReturn(8L, 12L);
        when(queries.dueRecoveryOrders(NOW, 0, 8, 2)).thenReturn(List.of(2L));
        when(queries.dueRecoveryOrders(NOW, 2, 8, 2)).thenReturn(List.of());
        when(queries.dueRecoveryOrders(NOW, 0, 12, 2)).thenReturn(List.of(12L));

        assertEquals(1, scanner.scanOnce());
        assertEquals(0, scanner.scanOnce());
        assertEquals(1, scanner.scanOnce());

        verify(queries, times(2)).recoveryUpperBound();
        verify(payments).recoverIfDue(2L);
        verify(payments).recoverIfDue(12L);
        verifyNoMoreInteractions(payments);
    }

    @Test
    void invalidBatchConfigurationCannotCreateAnUnboundedOrInactiveScanner() {
        for (int batchSize : new int[] {-1, 0, 501}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new PaymentRecoveryScanner(queries, payments, clock, batchSize));
        }
        verifyNoInteractions(queries, payments);
    }
}
