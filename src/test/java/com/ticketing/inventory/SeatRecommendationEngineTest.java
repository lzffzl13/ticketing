package com.ticketing.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ticketing.inventory.InventoryModels.SeatStatus;
import com.ticketing.inventory.InventoryModels.SessionSeat;
import com.ticketing.inventory.SeatRecommendationModels.Candidate;
import com.ticketing.inventory.SeatRecommendationModels.Diagnostics;
import com.ticketing.inventory.SeatRecommendationModels.Result;

import static org.junit.jupiter.api.Assertions.*;

class SeatRecommendationEngineTest {
    private final SeatSelectionRules rules = new SeatSelectionRules();
    private final SeatRecommendationEngine engine = new SeatRecommendationEngine(rules);

    @Test
    void emptyLayoutReturnsZeroDiagnostics() {
        Result result = recommend(List.of(), 2, true);

        assertTrue(result.candidates().isEmpty());
        assertEquals(new Diagnostics(0, 0, 0, 0, 0), result.diagnostics());
    }

    @Test
    void exactSizeRunCanBeRecommendedWithoutLeavingAnOrphan() {
        Result result = recommend(row(3), 3, true);

        assertEquals(List.of(List.of(1L, 2L, 3L)), ids(result));
        assertEquals(new Diagnostics(3, 1, 0, 0, 1), result.diagnostics());
        assertTrue(result.candidates().getFirst().reason().contains("未制造新孤座"));
    }

    @Test
    void tooShortRunIsCountedWithoutAProposedPartialGroup() {
        Result result = recommend(row(2), 3, true);

        assertTrue(result.candidates().isEmpty());
        assertEquals(new Diagnostics(2, 1, 1, 0, 0), result.diagnostics());
    }

    @Test
    void unavailableHeldAndSoldSeatsSplitAvailableRuns() {
        for (SeatStatus status : List.of(SeatStatus.UNAVAILABLE, SeatStatus.HELD, SeatStatus.SOLD)) {
            List<SessionSeat> layout = List.of(seat(1, 1).build(), seat(2, 2).status(status).build(),
                    seat(3, 3).build());

            Result result = recommend(layout, 2, false);

            assertTrue(result.candidates().isEmpty(), status.toString());
            assertEquals(new Diagnostics(2, 2, 2, 0, 0), result.diagnostics());
        }
    }

    @Test
    void consecutiveNumbersAcrossAnAisleAreNotRecommendedTogether() {
        List<SessionSeat> layout = List.of(seat(1, 1).aisle("LEFT").build(),
                seat(2, 2).aisle("RIGHT").build());

        assertTrue(recommend(layout, 2, false).candidates().isEmpty());
    }

    @Test
    void missingSeatNumbersSplitRuns() {
        List<SessionSeat> layout = List.of(seat(1, 1).build(), seat(3, 3).build());

        assertTrue(recommend(layout, 2, false).candidates().isEmpty());
    }

    @Test
    void differentRowsCannotFormOneCandidate() {
        List<SessionSeat> layout = List.of(seat(1, 1).row("A", 1).build(),
                seat(2, 2).row("B", 2).build());

        assertTrue(recommend(layout, 2, false).candidates().isEmpty());
    }

    @Test
    void differentAreasCannotFormOneCandidate() {
        List<SessionSeat> layout = List.of(seat(1, 1).area(10, "A", 1).build(),
                seat(2, 2).area(20, "B", 2).build());

        assertTrue(recommend(layout, 2, false).candidates().isEmpty());
    }

    @Test
    void ticketTierBoundariesRemainPresentWhenScanningAllEnabledTiers() {
        List<SessionSeat> layout = List.of(seat(1, 1).build(), seat(2, 2).build(),
                seat(3, 3).tier(2, "200.00").build(), seat(4, 4).tier(2, "200.00").build());

        Result result = engine.recommend(layout, Set.of(1L, 2L), null, null, 2, 20, false);

        assertEquals(Set.of(List.of(1L, 2L), List.of(3L, 4L)), Set.copyOf(ids(result)));
        assertEquals(new Diagnostics(4, 2, 0, 0, 2), result.diagnostics());
    }

    @Test
    void disabledTierIsExcludedWithoutChangingItsAvailableInventory() {
        List<SessionSeat> layout = List.of(seat(1, 1).build(), seat(2, 2).build(),
                seat(3, 3).tier(2, "200.00").build(), seat(4, 4).tier(2, "200.00").build());

        Result result = recommend(layout, 2, true);

        assertEquals(List.of(List.of(1L, 2L)), ids(result));
        assertEquals(new Diagnostics(2, 1, 0, 0, 1), result.diagnostics());
        assertTrue(layout.stream().allMatch(s -> s.seatStatus() == SeatStatus.AVAILABLE));
    }

    @Test
    void areaFilterOnlyIncludesTheRequestedArea() {
        List<SessionSeat> layout = List.of(seat(1, 1).build(), seat(2, 2).build(),
                seat(3, 1).area(20, "B", 2).build(), seat(4, 2).area(20, "B", 2).build());

        Result result = engine.recommend(layout, Set.of(1L), "B", null, 2, 20, true);

        assertEquals(List.of(List.of(3L, 4L)), ids(result));
        assertEquals(new Diagnostics(2, 1, 0, 0, 1), result.diagnostics());
    }

    @Test
    void priceTierFilterOnlyIncludesTheRequestedTier() {
        List<SessionSeat> layout = List.of(seat(1, 1).build(), seat(2, 2).build(),
                seat(3, 3).tier(2, "200.00").build(), seat(4, 4).tier(2, "200.00").build());

        Result result = engine.recommend(layout, Set.of(1L, 2L), null, 2L, 2, 20, true);

        assertEquals(List.of(List.of(3L, 4L)), ids(result));
    }

    @Test
    void areaAndPriceTierFiltersAreAppliedTogether() {
        List<SessionSeat> layout = List.of(seat(1, 1).tier(2, "200.00").build(),
                seat(2, 1).area(20, "B", 2).build());

        Result result = engine.recommend(layout, Set.of(1L, 2L), "B", 2L, 1, 20, true);

        assertTrue(result.candidates().isEmpty());
        assertEquals(new Diagnostics(0, 0, 0, 0, 0), result.diagnostics());
    }

    @Test
    void totalPriceUsesTheSeatSnapshotsAndExactDecimalAddition() {
        List<SessionSeat> layout = List.of(seat(1, 1).tier(1, "123.45").build(),
                seat(2, 2).tier(1, "123.45").build(), seat(3, 3).tier(1, "123.45").build());

        Candidate candidate = recommend(layout, 3, true).candidates().getFirst();

        assertDecimal("370.35", candidate.totalPrice());
    }

    @Test
    void nonAvailableSeatsStillDefineTheAreaBoundingBoxCenter() {
        for (SeatStatus status : List.of(SeatStatus.UNAVAILABLE, SeatStatus.HELD, SeatStatus.SOLD)) {
            List<SessionSeat> layout = List.of(seat(1, 1).coordinates("0", "0").build(),
                    seat(2, 2).coordinates("2", "0").build(),
                    seat(3, 3).coordinates("8", "0").status(status).build());

            assertDecimal("9", recommend(layout, 2, true).candidates().getFirst().centerDistanceSquared());
        }
    }

    @Test
    void ticketTierFilterDoesNotMoveTheAreaCenter() {
        List<SessionSeat> layout = List.of(seat(1, 1).coordinates("0", "0").build(),
                seat(2, 2).coordinates("2", "0").build(),
                seat(3, 3).coordinates("8", "0").tier(2, "200.00").build());

        Result result = engine.recommend(layout, Set.of(1L, 2L), null, 1L, 2, 20, true);

        assertDecimal("9", result.candidates().getFirst().centerDistanceSquared());
    }

    @Test
    void areaDisplayOrderTakesPriorityOverDistancesInIndependentCoordinateSpaces() {
        List<SessionSeat> layout = List.of(seat(1, 1).area(10, "A", 2).coordinates("0", "0").build(),
                seat(2, 1).area(20, "B", 1).coordinates("0", "0").build(),
                seat(3, 2).area(20, "B", 1).coordinates("100", "0").status(SeatStatus.SOLD).build());

        assertEquals(List.of(List.of(2L), List.of(1L)), ids(recommend(layout, 1, true)));
    }

    @Test
    void nearestCandidateComesFirstWithStableSeatNumberTieBreaking() {
        Result result = recommend(row(5), 2, false);

        assertEquals(List.of(List.of(2L, 3L), List.of(3L, 4L), List.of(1L, 2L), List.of(4L, 5L)), ids(result));
        assertDecimal("0.25", result.candidates().getFirst().centerDistanceSquared());
    }

    @Test
    void rowOrderThenRowLabelBreakEqualDistanceTies() {
        List<SessionSeat> layout = List.of(seat(1, 1).row("Z", 1).coordinates("0", "0").build(),
                seat(2, 1).row("B", 2).coordinates("0", "0").build(),
                seat(3, 1).row("A", 2).coordinates("0", "0").build());

        assertEquals(List.of(List.of(1L), List.of(3L), List.of(2L)), ids(recommend(layout, 1, true)));
    }

    @Test
    void equalAreaDisplayOrdersUseAreaCodeThenSourceAreaIdBeforeDistance() {
        List<SessionSeat> layout = List.of(seat(1, 1).area(10, "B", 1).build(),
                seat(2, 1).area(30, "A", 1).build(), seat(3, 1).area(20, "A", 1).build());

        assertEquals(List.of(List.of(3L), List.of(2L), List.of(1L)), ids(recommend(layout, 1, true)));
    }

    @Test
    void arbitraryInputOrderProducesTheSameCandidatesDiagnosticsAndScores() {
        List<SessionSeat> layout = new ArrayList<>(row(7));
        layout.add(seat(20, 1).area(20, "B", 1).coordinates("10", "0").build());
        Result expected = recommend(layout, 2, false);
        Random random = new Random(20261005L);

        for (int sample = 0; sample < 50; sample++) {
            Collections.shuffle(layout, random);
            assertEquals(expected, recommend(layout, 2, false));
        }
    }

    @Test
    void forbiddenOrphansRejectOnlyWindowsLeavingOneSeatOnEitherSide() {
        Result result = recommend(row(4), 2, true);

        assertEquals(List.of(List.of(1L, 2L), List.of(3L, 4L)), ids(result));
        assertEquals(new Diagnostics(4, 1, 0, 1, 2), result.diagnostics());
        Result noLegalWindow = recommend(row(3), 2, true);
        assertTrue(noLegalWindow.candidates().isEmpty());
        assertEquals(new Diagnostics(3, 1, 0, 2, 0), noLegalWindow.diagnostics());
    }

    @Test
    void existingOrphanCanBePurchased() {
        Result result = recommend(row(1), 1, true);

        assertEquals(List.of(List.of(1L)), ids(result));
        assertEquals(0, result.diagnostics().newOrphanRejectedCount());
    }

    @Test
    void disabledOrphanRuleAllowsTheMiddleWindowAsWell() {
        Result result = recommend(row(4), 2, false);

        assertEquals(List.of(List.of(2L, 3L), List.of(1L, 2L), List.of(3L, 4L)), ids(result));
        assertEquals(new Diagnostics(4, 1, 0, 0, 3), result.diagnostics());
        assertTrue(result.candidates().getFirst().reason().contains("场次允许制造孤座"));
    }

    @Test
    void diagnosticsCountAllEligibleRunsAndWindowsBeforeTheDisplayLimit() {
        List<SessionSeat> layout = new ArrayList<>(row(4));
        for (int number = 1; number <= 3; number++) {
            layout.add(seat(10 + number, number).row("B", 2).build());
        }
        layout.add(seat(20, 1).row("C", 3).build());
        layout.add(seat(30, 1).row("D", 4).tier(2, "200.00").build());
        layout.add(seat(31, 2).row("D", 4).tier(2, "200.00").build());

        Result result = engine.recommend(layout, Set.of(1L), null, null, 2, 1, true);

        assertEquals(1, result.candidates().size());
        assertEquals(new Diagnostics(8, 3, 1, 3, 2), result.diagnostics());
    }

    @Test
    void quantityHasNoArtificialTwentySeatPurchaseLimit() {
        assertEquals(21, recommend(row(21), 21, true).candidates().getFirst().seats().size());
        Result insufficient = recommend(row(21), Integer.MAX_VALUE, true);
        assertEquals(new Diagnostics(21, 1, 1, 0, 0), insufficient.diagnostics());
    }

    @Test
    void invalidQuantityOrDisplayLimitIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> recommend(row(2), 0, true));
        assertThrows(IllegalArgumentException.class, () -> recommend(row(2), -1, true));
        assertThrows(IllegalArgumentException.class,
                () -> engine.recommend(row(2), Set.of(1L), null, null, 1, 0, true));
        assertThrows(IllegalArgumentException.class,
                () -> engine.recommend(row(2), Set.of(1L), null, null, 1, 21, true));
    }

    @Test
    void repeatingDecimalCandidateCenterUsesTwelvePlacesAndHalfUp() {
        List<SessionSeat> layout = List.of(seat(1, 1).coordinates("0", "0").build(),
                seat(2, 2).coordinates("0", "0").build(), seat(3, 3).coordinates("1", "0").build());

        assertDecimal("0.027777777777888888888889",
                recommend(layout, 3, true).candidates().getFirst().centerDistanceSquared());
    }

    @Test
    void everyReturnedCandidatePassesTheSharedSelectionRules() {
        List<SessionSeat> layout = row(9);

        for (boolean forbid : List.of(true, false)) {
            for (int quantity = 1; quantity <= layout.size(); quantity++) {
                for (Candidate candidate : recommend(layout, quantity, forbid).candidates()) {
                    List<Long> selectedIds = candidate.seats().stream().map(SessionSeat::id).toList();
                    assertEquals(quantity, selectedIds.size());
                    assertEquals("OK", rules.validate(layout, selectedIds, true, forbid).code());
                }
            }
        }
    }

    @Test
    void oldCandidateFailsRevalidationAfterASeatBecomesHeld() {
        List<SessionSeat> oldLayout = row(2);
        List<Long> selectedIds = ids(recommend(oldLayout, 2, true)).getFirst();
        List<SessionSeat> latestLayout = List.of(seat(1, 1).build(), seat(2, 2).status(SeatStatus.HELD).build());

        assertEquals("SEAT_NOT_AVAILABLE", rules.validate(latestLayout, selectedIds, true, true).code());
        assertTrue(recommend(latestLayout, 2, true).candidates().isEmpty());
        assertEquals(SeatStatus.AVAILABLE, oldLayout.get(1).seatStatus());
    }

    @Test
    void repeatedQueriesDoNotModifyTheirInputsAndReturnImmutableResults() {
        List<SessionSeat> layout = new ArrayList<>(row(4));
        Collections.reverse(layout);
        List<SessionSeat> before = List.copyOf(layout);
        Result first = recommend(layout, 2, true);

        assertEquals(first, recommend(layout, 2, true));
        assertEquals(before, layout);
        assertThrows(UnsupportedOperationException.class, () -> first.candidates().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.candidates().getFirst().seats().clear());
    }

    private Result recommend(List<SessionSeat> layout, int quantity, boolean forbidNewOrphan) {
        return engine.recommend(layout, Set.of(1L), null, null, quantity, 20, forbidNewOrphan);
    }

    private static List<List<Long>> ids(Result result) {
        return result.candidates().stream()
                .map(candidate -> candidate.seats().stream().map(SessionSeat::id).toList()).toList();
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected decimal " + expected);
    }

    private static List<SessionSeat> row(int size) {
        List<SessionSeat> seats = new ArrayList<>();
        for (int number = 1; number <= size; number++) {
            seats.add(seat(number, number).build());
        }
        return List.copyOf(seats);
    }

    private static SeatBuilder seat(long id, int number) {
        return new SeatBuilder(id, number);
    }

    private static final class SeatBuilder {
        private final long id;
        private final int number;
        private long areaId = 10;
        private String areaCode = "A";
        private int areaOrder = 1;
        private String row = "A";
        private int rowOrder = 1;
        private BigDecimal x;
        private BigDecimal y = BigDecimal.ZERO;
        private String aisle = "MAIN";
        private long tierId = 1;
        private BigDecimal price = new BigDecimal("100.00");
        private SeatStatus status = SeatStatus.AVAILABLE;

        private SeatBuilder(long id, int number) {
            this.id = id;
            this.number = number;
            this.x = BigDecimal.valueOf(number);
        }

        SeatBuilder area(long areaId, String code, int order) {
            this.areaId = areaId;
            this.areaCode = code;
            this.areaOrder = order;
            return this;
        }

        SeatBuilder row(String label, int order) {
            this.row = label;
            this.rowOrder = order;
            return this;
        }

        SeatBuilder coordinates(String x, String y) {
            this.x = new BigDecimal(x);
            this.y = new BigDecimal(y);
            return this;
        }

        SeatBuilder aisle(String segment) {
            this.aisle = segment;
            return this;
        }

        SeatBuilder tier(long id, String amount) {
            this.tierId = id;
            this.price = new BigDecimal(amount);
            return this;
        }

        SeatBuilder status(SeatStatus status) {
            this.status = status;
            return this;
        }

        SessionSeat build() {
            return new SessionSeat(id, 1L, id, areaId, areaCode, areaCode, areaOrder, row, rowOrder, number,
                    x, y, aisle, tierId, "Tier " + tierId, price, status, Instant.EPOCH, Instant.EPOCH);
        }
    }
}
