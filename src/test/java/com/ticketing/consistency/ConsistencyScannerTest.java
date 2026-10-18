package com.ticketing.consistency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import static com.ticketing.consistency.ConsistencyModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsistencyScannerTest {
    private ConsistencyService consistency;

    @BeforeEach
    void prepare() {
        consistency = mock(ConsistencyService.class);
        when(consistency.inspect(anyLong())).thenAnswer(call -> new SessionReport(call.getArgument(0), Instant.EPOCH, true,
                new Counts(0, 0, 0, 0, 0), new RecoverySummary(0, 0, 0, 0, 0), List.of()));
    }

    @Test
    void failedSessionDoesNotBlockOtherSessionsAndWillBeVisitedAfterWrap() {
        var scanner = new ConsistencyScanner(consistency, 2);
        when(consistency.upperBound()).thenReturn(2L);
        when(consistency.sessions(0, 2, 2)).thenReturn(List.of(1L, 2L));
        when(consistency.inspect(1L)).thenThrow(new IllegalStateException("test session snapshot unavailable"));
        assertEquals(2, scanner.scanOnce());
        assertEquals(2, scanner.scanOnce());
        verify(consistency, times(2)).inspect(1L);
        verify(consistency, times(2)).inspect(2L);
    }

    @Test
    void failedCandidateQueryRetainsCursorAndTheSameUpperBound() {
        var scanner = new ConsistencyScanner(consistency, 1);
        when(consistency.upperBound()).thenReturn(5L);
        when(consistency.sessions(0, 5, 1)).thenReturn(List.of(1L));
        when(consistency.sessions(1, 5, 1)).thenThrow(new IllegalStateException("test list unavailable")).thenReturn(List.of(5L));
        assertEquals(1, scanner.scanOnce());
        assertThrows(IllegalStateException.class, scanner::scanOnce);
        assertEquals(1, scanner.scanOnce());
        verify(consistency).upperBound();
        verify(consistency, times(2)).sessions(1, 5, 1);
        verify(consistency).inspect(5L);
    }

    @Test
    void fixedUpperBoundWrapsToLowIdsBeforeContinuouslyArrivingHigherIds() {
        var scanner = new ConsistencyScanner(consistency, 1);
        when(consistency.upperBound()).thenReturn(2L, 4L);
        when(consistency.sessions(0, 2, 1)).thenReturn(List.of(1L));
        when(consistency.sessions(1, 2, 1)).thenReturn(List.of(2L));
        when(consistency.sessions(0, 4, 1)).thenReturn(List.of(1L));
        assertEquals(1, scanner.scanOnce());
        assertEquals(1, scanner.scanOnce());
        assertEquals(1, scanner.scanOnce());
        var traversal = inOrder(consistency);
        traversal.verify(consistency).upperBound();
        traversal.verify(consistency).sessions(0, 2, 1);
        traversal.verify(consistency).inspect(1L);
        traversal.verify(consistency).sessions(1, 2, 1);
        traversal.verify(consistency).inspect(2L);
        traversal.verify(consistency).upperBound();
        traversal.verify(consistency).sessions(0, 4, 1);
        traversal.verify(consistency).inspect(1L);
    }

    @Test
    void emptyRemainingBatchStartsANewTraversalAndUpperBoundFailureCanRetry() {
        var scanner = new ConsistencyScanner(consistency, 2);
        when(consistency.upperBound()).thenThrow(new IllegalStateException("test max unavailable")).thenReturn(3L, 4L);
        when(consistency.sessions(0, 3, 2)).thenReturn(List.of(1L));
        when(consistency.sessions(1, 3, 2)).thenReturn(List.of());
        when(consistency.sessions(0, 4, 2)).thenReturn(List.of(4L));
        assertThrows(IllegalStateException.class, scanner::scanOnce);
        assertEquals(1, scanner.scanOnce()); assertEquals(0, scanner.scanOnce()); assertEquals(1, scanner.scanOnce());
        verify(consistency, times(3)).upperBound();
        verify(consistency).inspect(4L);
    }

    @Test
    void differencesAndManualRecoveryAreReportedWithoutInterruptingTheBatch() {
        var scanner = new ConsistencyScanner(consistency, 10);
        when(consistency.upperBound()).thenReturn(1L);
        when(consistency.sessions(0, 1, 10)).thenReturn(List.of(1L));
        when(consistency.inspect(1L)).thenReturn(new SessionReport(1, Instant.EPOCH, false,
                new Counts(1, 1, 0, 0, 0), new RecoverySummary(1, 0, 1, 0, 1),
                List.of(new Difference("C02_PENDING_OWNERSHIP", "ORDER", 1, Severity.ERROR, "missing hold", "人工核查"))));
        assertEquals(1, scanner.scanOnce());
        verify(consistency).inspect(1L);
    }

    @Test
    void invalidBatchSizeIsRejected() {
        for (int size : new int[] {-1, 0, 101}) { assertThrows(IllegalArgumentException.class, () -> new ConsistencyScanner(consistency, size)); }
    }
}
