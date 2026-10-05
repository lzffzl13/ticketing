package com.ticketing.inventory;

import com.ticketing.inventory.InventoryModels.SeatStatus;
import com.ticketing.inventory.InventoryModels.SessionSeat;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeatSelectionRulesTest {
    private final SeatSelectionRules rules = new SeatSelectionRules();

    @Test
    void buyingTheWholeRunLeavesNoOrphan() {
        assertAccepted(row(4), List.of(1L, 2L, 3L, 4L), true, true);
    }

    @Test
    void adjacentMiddleSeatsAreAllowedWhenBothRemainingRunsHaveTwoSeats() {
        assertAccepted(row(6), List.of(3L, 4L), true, true);
    }

    @Test
    void consecutiveSeatNumbersDoNotJoinAcrossAnAisle() {
        var layout = List.of(seat(1, 1, "A", 1, 10L, "L", SeatStatus.AVAILABLE),
                seat(2, 1, "A", 2, 10L, "R", SeatStatus.AVAILABLE));
        assertRejected(layout, List.of(1L, 2L), true, false, "NOT_ADJACENT", List.of());
    }

    @Test
    void missingSeatNumbersBreakAdjacency() {
        var layout = List.of(seat(1, 1, "A", 1, 10L, "L", SeatStatus.AVAILABLE),
                seat(3, 1, "A", 3, 10L, "L", SeatStatus.AVAILABLE));
        assertRejected(layout, List.of(1L, 3L), true, false, "NOT_ADJACENT", List.of());
    }

    @Test
    void differentAreasBreakAdjacencyEvenWhenRowAndNumbersMatch() {
        var layout = List.of(seat(1, 1, "A", 1, 10L, "L", SeatStatus.AVAILABLE),
                seat(2, 2, "A", 2, 10L, "L", SeatStatus.AVAILABLE));
        assertRejected(layout, List.of(1L, 2L), true, false, "NOT_ADJACENT", List.of());
    }

    @Test
    void differentRowsBreakAdjacency() {
        var layout = List.of(seat(1, 1, "A", 1, 10L, "L", SeatStatus.AVAILABLE),
                seat(2, 1, "B", 2, 10L, "L", SeatStatus.AVAILABLE));
        assertRejected(layout, List.of(1L, 2L), true, false, "NOT_ADJACENT", List.of());
    }

    @Test
    void differentPriceTiersBreakAdjacency() {
        var layout = List.of(seat(1, 1, "A", 1, 10L, "L", SeatStatus.AVAILABLE),
                seat(2, 1, "A", 2, 20L, "L", SeatStatus.AVAILABLE));
        assertRejected(layout, List.of(1L, 2L), true, false, "NOT_ADJACENT", List.of());
    }

    @Test
    void unavailableSeatsCannotBeSelectedEvenWhenOrphanAndAdjacentRulesAreDisabled() {
        assertRejected(List.of(withStatus(1, SeatStatus.UNAVAILABLE)), List.of(1L), false, false,
                "SEAT_NOT_AVAILABLE", List.of());
    }

    @Test
    void heldSeatsCannotBeSelected() {
        assertRejected(List.of(withStatus(1, SeatStatus.HELD)), List.of(1L), true, true,
                "SEAT_NOT_AVAILABLE", List.of());
    }

    @Test
    void soldSeatsCannotBeSelected() {
        assertRejected(List.of(withStatus(1, SeatStatus.SOLD)), List.of(1L), true, true,
                "SEAT_NOT_AVAILABLE", List.of());
    }

    @Test
    void selectingAllButTheFirstSeatCreatesANewRowStartOrphan() {
        assertRejected(row(4), List.of(2L, 3L, 4L), true, true, "NEW_ORPHAN", List.of(1L));
    }

    @Test
    void selectingAllButTheLastSeatCreatesANewRowEndOrphan() {
        assertRejected(row(4), List.of(1L, 2L, 3L), true, true, "NEW_ORPHAN", List.of(4L));
    }

    @Test
    void aMiddleSelectionCanCreateTwoNewOrphans() {
        assertRejected(row(5), List.of(2L, 3L, 4L), true, true, "NEW_ORPHAN", List.of(1L, 5L));
    }

    @Test
    void anExistingOrphanBetweenAHeldAndSoldSeatCanBePurchased() {
        var layout = List.of(withStatus(1, SeatStatus.HELD), withStatus(2, SeatStatus.AVAILABLE),
                withStatus(3, SeatStatus.SOLD));
        assertAccepted(layout, List.of(2L), true, true);
    }

    @Test
    void anUnchangedExistingOrphanDoesNotRejectAnUnrelatedSelection() {
        var layout = new ArrayList<>(row(4));
        layout.add(seat(9, 2, "A", 1, 20L, "L", SeatStatus.AVAILABLE));
        assertAccepted(layout, List.of(1L, 2L), true, true);
    }

    @Test
    void buyingAnOldOrphanDoesNotCancelOutCreatingADifferentNewOrphan() {
        var layout = new ArrayList<>(row(3));
        layout.add(seat(9, 2, "A", 1, 20L, "L", SeatStatus.AVAILABLE));
        // Before: orphan 9. After: orphan 1. Equal counts still violate the identity-based rule.
        assertRejected(layout, List.of(9L, 2L, 3L), false, true, "NEW_ORPHAN", List.of(1L));
    }

    @Test
    void anExplicitlyRelaxedSessionCanCreateNewOrphans() {
        assertAccepted(row(5), List.of(2L, 3L, 4L), true, false);
    }

    @Test
    void freeSelectionAllowsGapsWhenNoSingleSeatRunIsCreated() {
        assertAccepted(row(8), List.of(3L, 6L), false, true);
        assertRejected(row(8), List.of(3L, 6L), true, true, "NOT_ADJACENT", List.of());
    }

    @Test
    void freeSelectionStillChecksTheWholeResultForNewOrphans() {
        assertRejected(row(8), List.of(3L, 5L), false, true, "NEW_ORPHAN", List.of(4L));
    }

    @Test
    void freeSelectionCanPurchaseExistingOrphansInDifferentAreasAndTiers() {
        var layout = List.of(seat(1, 1, "A", 1, 10L, "L", SeatStatus.AVAILABLE),
                seat(2, 2, "B", 3, 20L, "R", SeatStatus.AVAILABLE));
        assertAccepted(layout, List.of(1L, 2L), false, true);
    }

    @Test
    void unavailablePositionWithNoTierCutsRunsAndDoesNotBecomeANewOrphan() {
        var layout = List.of(withStatus(1, SeatStatus.AVAILABLE),
                seat(2, 1, "A", 2, null, "L", SeatStatus.UNAVAILABLE),
                withStatus(3, SeatStatus.AVAILABLE), withStatus(4, SeatStatus.AVAILABLE));
        assertAccepted(layout, List.of(1L), true, true);
        assertRejected(layout, List.of(4L), true, true, "NEW_ORPHAN", List.of(3L));
    }

    @Test
    void heldPositionsCutTheAvailableRunBeforeAndAfterSelection() {
        var layout = List.of(withStatus(1, SeatStatus.AVAILABLE), withStatus(2, SeatStatus.AVAILABLE),
                withStatus(3, SeatStatus.HELD), withStatus(4, SeatStatus.AVAILABLE));
        assertAccepted(layout, List.of(4L), true, true);
        assertRejected(layout, List.of(2L), true, true, "NEW_ORPHAN", List.of(1L));
    }

    @Test
    void soldPositionsCutTheAvailableRunBeforeAndAfterSelection() {
        var layout = List.of(withStatus(1, SeatStatus.AVAILABLE), withStatus(2, SeatStatus.SOLD),
                withStatus(3, SeatStatus.AVAILABLE), withStatus(4, SeatStatus.AVAILABLE));
        assertAccepted(layout, List.of(1L), true, true);
        assertRejected(layout, List.of(3L), true, true, "NEW_ORPHAN", List.of(4L));
    }

    @Test
    void aMissingNumberCreatesSeparateRunsForOrphanComparison() {
        var layout = List.of(withStatus(1, SeatStatus.AVAILABLE), withStatus(2, SeatStatus.AVAILABLE),
                withStatus(4, SeatStatus.AVAILABLE));
        assertAccepted(layout, List.of(4L), true, true);
        assertRejected(layout, List.of(2L), true, true, "NEW_ORPHAN", List.of(1L));
    }

    @Test
    void aisleAndTierBoundariesCreateSeparateRunsForOrphanComparison() {
        var aisleLayout = List.of(withStatus(1, SeatStatus.AVAILABLE), withStatus(2, SeatStatus.AVAILABLE),
                seat(3, 1, "A", 3, 10L, "R", SeatStatus.AVAILABLE));
        var tierLayout = List.of(withStatus(1, SeatStatus.AVAILABLE), withStatus(2, SeatStatus.AVAILABLE),
                seat(3, 1, "A", 3, 20L, "L", SeatStatus.AVAILABLE));
        assertRejected(aisleLayout, List.of(2L), true, true, "NEW_ORPHAN", List.of(1L));
        assertRejected(tierLayout, List.of(2L), true, true, "NEW_ORPHAN", List.of(1L));
    }

    @Test
    void selectionDoesNotModifyOrReorderCallerDataOrSeatState() {
        var layout = new ArrayList<>(row(5));
        Collections.reverse(layout);
        var selected = new ArrayList<>(List.of(4L, 2L, 3L));
        var beforeLayout = List.copyOf(layout);
        var beforeSelected = List.copyOf(selected);
        var result = rules.validate(layout, selected, true, true);

        assertEquals("NEW_ORPHAN", result.code());
        assertEquals(beforeLayout, layout);
        assertEquals(beforeSelected, selected);
        assertTrue(layout.stream().allMatch(seat -> seat.seatStatus() == SeatStatus.AVAILABLE));
        assertThrows(UnsupportedOperationException.class, () -> result.newOrphanSeatIds().add(9L));
    }

    @Test
    void layoutAndSelectionInputOrderDoNotAffectResults() {
        var shuffledLayout = new ArrayList<>(row(5));
        Collections.rotate(shuffledLayout, 2);
        assertEquals(rules.validate(row(5), List.of(2L, 3L, 4L), true, true),
                rules.validate(shuffledLayout, List.of(4L, 2L, 3L), true, true));
        assertAccepted(shuffledLayout, List.of(5L, 1L, 4L, 2L, 3L), true, true);
    }

    @Test
    void freeSelectionIsCheckedAsOneBatchRatherThanIntermediateSingleSeatRemovals() {
        // Taking seat 2 alone would isolate seat 1, but taking the whole prefix leaves a run of 3.
        assertAccepted(row(6), List.of(2L, 1L, 3L), false, true);
    }

    @Test
    void availableRunsCutOnEveryUnavailableStatusAndOnMissingNumbers() {
        var layout = List.of(withStatus(1, SeatStatus.AVAILABLE), withStatus(2, SeatStatus.AVAILABLE),
                withStatus(3, SeatStatus.UNAVAILABLE), withStatus(4, SeatStatus.AVAILABLE),
                withStatus(5, SeatStatus.HELD), withStatus(6, SeatStatus.AVAILABLE),
                withStatus(7, SeatStatus.SOLD), withStatus(8, SeatStatus.AVAILABLE),
                withStatus(10, SeatStatus.AVAILABLE));
        assertEquals(List.of(List.of(1L, 2L), List.of(4L), List.of(6L), List.of(8L), List.of(10L)),
                runIds(layout));
    }

    @Test
    void availableRunsCutOnAreaRowTierAndAisleBoundaries() {
        var layout = List.of(seat(1, 1, "A", 1, 10L, "L", SeatStatus.AVAILABLE),
                seat(2, 1, "A", 2, 20L, "L", SeatStatus.AVAILABLE),
                seat(3, 1, "A", 3, 20L, "R", SeatStatus.AVAILABLE),
                seat(4, 1, "B", 4, 20L, "R", SeatStatus.AVAILABLE),
                seat(5, 2, "B", 5, 20L, "R", SeatStatus.AVAILABLE));
        assertEquals(List.of(List.of(1L), List.of(2L), List.of(3L), List.of(4L), List.of(5L)),
                runIds(layout));
    }

    @Test
    void availableRunsFollowStableAreaRowAndSeatOrderWithoutChangingInput() {
        var layout = new ArrayList<>(List.of(
                seat(10, 2, "B", 1, 20L, "L", SeatStatus.AVAILABLE),
                seat(5, 1, "B", 1, 10L, "L", SeatStatus.AVAILABLE),
                seat(2, 1, "A", 2, 10L, "L", SeatStatus.AVAILABLE),
                seat(1, 1, "A", 1, 10L, "L", SeatStatus.AVAILABLE)));
        var before = List.copyOf(layout);
        assertEquals(List.of(List.of(1L, 2L), List.of(5L), List.of(10L)), runIds(layout));
        assertEquals(before, layout);
        assertEquals(List.of(), rules.availableRuns(List.of()));
    }

    @Test
    void availableRunsAreImmutableAtBothListLevels() {
        var runs = rules.availableRuns(row(2));
        assertThrows(UnsupportedOperationException.class, () -> runs.add(List.of()));
        assertThrows(UnsupportedOperationException.class, () -> runs.getFirst().clear());
    }

    @Test
    void contiguousWindowOrphanShortcutMatchesFullValidationForEverySmallRunWindow() {
        for (int size = 1; size <= 12; size++) {
            var layout = row(size);
            for (int start = 0; start < size; start++) {
                for (int quantity = 1; quantity <= size - start; quantity++) {
                    var ids = layout.subList(start, start + quantity).stream().map(SessionSeat::id).toList();
                    var result = rules.validate(layout, ids, true, true);
                    assertEquals(!result.valid(), rules.createsNewOrphan(size, start, quantity),
                            "runSize=" + size + ", start=" + start + ", quantity=" + quantity);
                }
            }
        }
    }

    @Test
    void contiguousWindowShortcutRejectsInvalidAndOverflowingBounds() {
        assertThrows(IllegalArgumentException.class, () -> rules.createsNewOrphan(0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> rules.createsNewOrphan(5, -1, 2));
        assertThrows(IllegalArgumentException.class, () -> rules.createsNewOrphan(5, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> rules.createsNewOrphan(5, 4, 2));
        assertThrows(IllegalArgumentException.class,
                () -> rules.createsNewOrphan(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void revalidationRejectsACandidateThatHasSinceBeenHeld() {
        assertAccepted(row(4), List.of(1L, 2L), true, true);
        var currentLayout = new ArrayList<>(row(4));
        currentLayout.set(1, withStatus(2, SeatStatus.HELD));
        assertRejected(currentLayout, List.of(1L, 2L), true, true, "SEAT_NOT_AVAILABLE", List.of());
        assertEquals(SeatStatus.AVAILABLE, currentLayout.getFirst().seatStatus());
    }

    @Test
    void revalidationDetectsANewOrphanCausedByAnotherUsersOccupancy() {
        assertAccepted(row(6), List.of(1L, 2L), true, true);
        var currentLayout = new ArrayList<>(row(6));
        currentLayout.set(3, withStatus(4, SeatStatus.HELD));
        assertRejected(currentLayout, List.of(1L, 2L), true, true, "NEW_ORPHAN", List.of(3L));
    }

    @Test
    void emptyNullUnknownDuplicateAndNullSelectionsAreRejected() {
        assertRejected(row(2), List.of(), false, false, "INVALID_SELECTION", List.of());
        assertRejected(row(2), null, false, false, "INVALID_SELECTION", List.of());
        assertRejected(row(2), List.of(99L), false, false, "INVALID_SELECTION", List.of());
        assertRejected(row(2), List.of(1L, 1L), false, false, "INVALID_SELECTION", List.of());
        assertRejected(row(2), Arrays.asList(1L, null), false, false, "INVALID_SELECTION", List.of());
    }

    @Test
    void invalidSelectionWinsOverUnavailableSeatRegardlessOfInputOrder() {
        var layout = List.of(withStatus(1, SeatStatus.HELD));
        assertRejected(layout, List.of(1L, 99L), false, false, "INVALID_SELECTION", List.of());
        assertRejected(layout, List.of(99L, 1L), false, false, "INVALID_SELECTION", List.of());
    }

    @Test
    void nullEmptyDuplicateAndMixedSessionLayoutsAreRejected() {
        assertRejected(null, List.of(1L), false, false, "INVALID_SELECTION", List.of());
        assertRejected(List.of(), List.of(1L), false, false, "INVALID_SELECTION", List.of());
        var seat = row(1).getFirst();
        assertRejected(Arrays.asList(seat, null), List.of(1L), false, false, "INVALID_SELECTION", List.of());
        assertRejected(List.of(seat, seat), List.of(1L), false, false, "INVALID_SELECTION", List.of());
        var otherSessionSeat = new SessionSeat(2L, 2L, 2L, 1L, "AREA-1", "Area", 1,
                "A", 1, 2, BigDecimal.valueOf(2), BigDecimal.ONE, "L", 10L, "Tier", BigDecimal.TEN,
                SeatStatus.AVAILABLE, Instant.EPOCH, Instant.EPOCH);
        assertRejected(List.of(seat, otherSessionSeat), List.of(1L, 2L), false, false,
                "INVALID_SELECTION", List.of());
    }

    private void assertAccepted(List<SessionSeat> layout, List<Long> ids, boolean adjacent, boolean forbidOrphan) {
        var result = rules.validate(layout, ids, adjacent, forbidOrphan);
        assertTrue(result.valid());
        assertEquals("OK", result.code());
        assertEquals(List.of(), result.newOrphanSeatIds());
    }

    private void assertRejected(List<SessionSeat> layout, List<Long> ids, boolean adjacent, boolean forbidOrphan,
                                String code, List<Long> orphans) {
        var result = rules.validate(layout, ids, adjacent, forbidOrphan);
        assertFalse(result.valid());
        assertEquals(code, result.code());
        assertEquals(orphans, result.newOrphanSeatIds());
    }

    private List<SessionSeat> row(int count) {
        var seats = new ArrayList<SessionSeat>();
        for (int number = 1; number <= count; number++) {
            seats.add(withStatus(number, SeatStatus.AVAILABLE));
        }
        return seats;
    }

    private List<List<Long>> runIds(List<SessionSeat> layout) {
        return rules.availableRuns(layout).stream()
                .map(run -> run.stream().map(SessionSeat::id).toList()).toList();
    }

    private SessionSeat withStatus(int number, SeatStatus status) {
        return seat(number, 1, "A", number, 10L, "L", status);
    }

    private SessionSeat seat(long id, long areaId, String row, int number, Long tierId,
                             String segment, SeatStatus status) {
        return new SessionSeat(id, 1L, id, areaId, "AREA-" + areaId, "Area " + areaId, (int) areaId,
                row, row.charAt(0) - 'A', number, BigDecimal.valueOf(number), BigDecimal.ZERO,
                segment, tierId, tierId == null ? null : "Tier " + tierId,
                tierId == null ? null : BigDecimal.TEN, status, Instant.EPOCH, Instant.EPOCH);
    }
}
