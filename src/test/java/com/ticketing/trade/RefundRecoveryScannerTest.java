package com.ticketing.trade;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefundRecoveryScannerTest {
    private static final Instant NOW = Instant.parse("2026-10-15T03:00:00.123456Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private RefundQueryService queries;
    private RefundService refunds;

    @BeforeEach
    void prepare() {
        queries = mock(RefundQueryService.class);
        refunds = mock(RefundService.class);
    }

    @Test
    void oneRefundFailureDoesNotPreventTheOtherOriginalRefundsFromBeingRecovered() {
        var scanner = new RefundRecoveryScanner(queries, refunds, clock, 3);
        when(queries.upperBound()).thenReturn(3L);
        when(queries.due(NOW, 0, 3, 3)).thenReturn(List.of(1L, 2L, 3L));
        when(refunds.recoverIfDue(1L)).thenThrow(new IllegalStateException("test first refund unavailable"));

        assertEquals(3, scanner.scanOnce());

        var processing = inOrder(refunds);
        processing.verify(refunds).recoverIfDue(1L);
        processing.verify(refunds).recoverIfDue(2L);
        processing.verify(refunds).recoverIfDue(3L);
    }

    @Test
    void databaseFailureRetainsTheLastSuccessfulCursorAndOriginalTraversalBound() {
        var scanner = new RefundRecoveryScanner(queries, refunds, clock, 2);
        when(queries.upperBound()).thenReturn(6L);
        when(queries.due(NOW, 0, 6, 2)).thenReturn(List.of(1L, 2L));
        when(queries.due(NOW, 2, 6, 2)).thenThrow(new IllegalStateException("test candidate query unavailable"))
                .thenReturn(List.of(5L, 6L));

        assertEquals(2, scanner.scanOnce());
        assertThrows(IllegalStateException.class, scanner::scanOnce);
        assertEquals(2, scanner.scanOnce());

        verify(queries, times(1)).upperBound();
        verify(queries, times(2)).due(NOW, 2, 6, 2);
        verify(refunds, times(1)).recoverIfDue(1L);
        verify(refunds, times(1)).recoverIfDue(2L);
        verify(refunds).recoverIfDue(5L);
        verify(refunds).recoverIfDue(6L);
    }

    @Test
    void finiteTraversalAdvancesPastPersistentFailuresAndWrapsBeforeTakingNewHigherIds() {
        var scanner = new RefundRecoveryScanner(queries, refunds, clock, 2);
        when(queries.upperBound()).thenReturn(6L, 9L);
        when(queries.due(NOW, 0, 6, 2)).thenReturn(List.of(1L, 2L));
        when(queries.due(NOW, 2, 6, 2)).thenReturn(List.of(4L, 6L));
        when(queries.due(NOW, 0, 9, 2)).thenReturn(List.of(1L, 2L));
        when(refunds.recoverIfDue(1L)).thenThrow(new IllegalStateException("test persistent low-id fault"));

        assertEquals(2, scanner.scanOnce());
        assertEquals(2, scanner.scanOnce());
        assertEquals(2, scanner.scanOnce());

        var traversal = inOrder(queries);
        traversal.verify(queries).upperBound();
        traversal.verify(queries).due(NOW, 0, 6, 2);
        traversal.verify(queries).due(NOW, 2, 6, 2);
        traversal.verify(queries).upperBound();
        traversal.verify(queries).due(NOW, 0, 9, 2);
        verify(refunds).recoverIfDue(4L);
        verify(refunds).recoverIfDue(6L);
        verify(refunds, times(2)).recoverIfDue(1L);
    }

    @Test
    void emptyTailWrapsAndLetsPreviouslyNotDueRefundsBecomeDiscoverable() {
        var scanner = new RefundRecoveryScanner(queries, refunds, clock, 2);
        when(queries.upperBound()).thenReturn(8L, 12L);
        when(queries.due(NOW, 0, 8, 2)).thenReturn(List.of(2L));
        when(queries.due(NOW, 2, 8, 2)).thenReturn(List.of());
        when(queries.due(NOW, 0, 12, 2)).thenReturn(List.of(12L));

        assertEquals(1, scanner.scanOnce());
        assertEquals(0, scanner.scanOnce());
        assertEquals(1, scanner.scanOnce());

        verify(queries, times(2)).upperBound();
        verify(refunds).recoverIfDue(2L);
        verify(refunds).recoverIfDue(12L);
        verifyNoMoreInteractions(refunds);
    }

    @Test
    void invalidBatchSizeCannotCreateAnInactiveOrUnboundedScanner() {
        for (int batch : new int[] {-1, 0, 501}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new RefundRecoveryScanner(queries, refunds, clock, batch));
        }
        verifyNoInteractions(queries, refunds);
    }
}
