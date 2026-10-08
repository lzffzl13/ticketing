package com.ticketing.inventory;

import com.ticketing.inventory.InventoryModels.SeatStatus;
import com.ticketing.inventory.InventoryModels.SessionSeat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure snapshot validation shared by recommendations and the future final hold check.
 * The caller supplies the complete layout of one session, including occupied and unavailable seats.
 * A successful result neither changes seat state nor reserves the selected seats.
 */
public final class SeatSelectionRules {
    private static final Comparator<SessionSeat> LAYOUT_ORDER = Comparator
            .comparingInt(SessionSeat::areaDisplayOrderSnapshot)
            .thenComparing(SessionSeat::sourceAreaId)
            .thenComparingInt(SessionSeat::rowOrderSnapshot)
            .thenComparing(SessionSeat::rowLabelSnapshot)
            .thenComparingInt(SessionSeat::seatNumberSnapshot)
            .thenComparing(SessionSeat::id);

    public record Validation(boolean valid, String code, List<Long> newOrphanSeatIds) {
        public Validation {
            newOrphanSeatIds = List.copyOf(newOrphanSeatIds);
        }
    }

    private record SegmentKey(Long areaId, String rowLabel, Long priceTierId, String aisleSegment) { }

    /**
     * Sales windows and price-tier enablement belong to the catalog caller, not this spatial rule.
     * @param requireAdjacent internal policy: false for manual selection, true for an adjacent
     *                        recommendation; consumers do not need a separate adjacency switch
     * @param forbidNewOrphan session policy, applied to the whole selection in either mode
     */
    public Validation validate(List<SessionSeat> fullLayout, List<Long> selectedIds,
                               boolean requireAdjacent, boolean forbidNewOrphan) {
        if (fullLayout == null || fullLayout.isEmpty() || selectedIds == null || selectedIds.isEmpty()) {
            return rejected("INVALID_SELECTION");
        }

        var byId = new HashMap<Long, SessionSeat>();
        Long sessionId = null;
        for (var seat : fullLayout) {
            if (seat == null || seat.id() == null || seat.sessionId() == null
                    || seat.sourceAreaId() == null || seat.rowLabelSnapshot() == null
                    || seat.aisleSegmentSnapshot() == null || seat.seatNumberSnapshot() <= 0
                    || seat.seatStatus() == null || byId.putIfAbsent(seat.id(), seat) != null) {
                return rejected("INVALID_SELECTION");
            }
            if (sessionId == null) {
                sessionId = seat.sessionId();
            } else if (!sessionId.equals(seat.sessionId())) {
                return rejected("INVALID_SELECTION");
            }
        }

        var selectedSet = new HashSet<Long>();
        for (var id : selectedIds) {
            if (id == null || !selectedSet.add(id) || !byId.containsKey(id)) {
                return rejected("INVALID_SELECTION");
            }
        }
        var selectedSeats = selectedSet.stream().map(byId::get).toList();
        if (selectedSeats.stream().anyMatch(seat -> seat.seatStatus() != SeatStatus.AVAILABLE)) {
            return rejected("SEAT_NOT_AVAILABLE");
        }
        if (requireAdjacent && !areAdjacent(selectedSeats)) {
            return rejected("NOT_ADJACENT");
        }
        if (!forbidNewOrphan) {
            return accepted();
        }

        // Existing single-seat runs remain legal. Only IDs newly isolated by this selection fail.
        var runs = availableRuns(fullLayout);
        var before = orphanIds(runs, Set.of());
        var after = orphanIds(runs, selectedSet);
        after.removeAll(before);
        if (!after.isEmpty()) {
            return new Validation(false, "NEW_ORPHAN", after.stream().sorted().toList());
        }
        return accepted();
    }

    /**
     * Deterministic available runs for recommendation windows. Occupied and unavailable positions
     * must remain in the input so they can cut the physical layout into separate runs.
     */
    public List<List<SessionSeat>> availableRuns(List<SessionSeat> fullLayout) {
        var ordered = fullLayout.stream().sorted(LAYOUT_ORDER).toList();
        var runs = new ArrayList<List<SessionSeat>>();
        var currentRun = new ArrayList<SessionSeat>();
        SessionSeat previous = null;
        for (var seat : ordered) {
            boolean available = seat.seatStatus() == SeatStatus.AVAILABLE;
            boolean continues = previous != null && segmentKey(previous).equals(segmentKey(seat))
                    && consecutive(previous, seat);
            if (!available || !continues) {
                if (!currentRun.isEmpty()) {
                    runs.add(List.copyOf(currentRun));
                    currentRun.clear();
                }
            }
            if (available) {
                currentRun.add(seat);
                previous = seat;
            } else {
                previous = null;
            }
        }
        if (!currentRun.isEmpty()) {
            runs.add(List.copyOf(currentRun));
        }
        return List.copyOf(runs);
    }

    /** A contiguous window only creates a new orphan if exactly one seat remains on either side. */
    public boolean createsNewOrphan(int runSize, int selectionStart, int quantity) {
        if (runSize <= 0 || selectionStart < 0 || quantity <= 0
                || (long) selectionStart + quantity > runSize) {
            throw new IllegalArgumentException("Selection must be a nonempty window inside an available run");
        }
        int leftRemaining = selectionStart;
        int rightRemaining = runSize - selectionStart - quantity;
        return leftRemaining == 1 || rightRemaining == 1;
    }

    private boolean areAdjacent(List<SessionSeat> selectedSeats) {
        var ordered = selectedSeats.stream()
                .sorted(Comparator.comparingInt(SessionSeat::seatNumberSnapshot)).toList();
        var firstKey = segmentKey(ordered.getFirst());
        for (int index = 0; index < ordered.size(); index++) {
            var current = ordered.get(index);
            if (!firstKey.equals(segmentKey(current))) {
                return false;
            }
            if (index > 0 && !consecutive(ordered.get(index - 1), current)) {
                return false;
            }
        }
        return true;
    }

    /** Runs already encode layout/state boundaries; the whole selection cuts each run once. */
    private Set<Long> orphanIds(List<List<SessionSeat>> runs, Set<Long> selectedIds) {
        var orphans = new HashSet<Long>();
        for (var seats : runs) {
            SessionSeat runStart = null;
            int runLength = 0;
            for (var seat : seats) {
                if (selectedIds.contains(seat.id())) {
                    if (runLength == 1) {
                        orphans.add(runStart.id());
                    }
                    runStart = null;
                    runLength = 0;
                } else {
                    if (runLength == 0) {
                        runStart = seat;
                    }
                    runLength++;
                }
            }
            if (runLength == 1) {
                orphans.add(runStart.id());
            }
        }
        return orphans;
    }

    private boolean consecutive(SessionSeat previous, SessionSeat current) {
        return (long) previous.seatNumberSnapshot() + 1 == current.seatNumberSnapshot();
    }

    private SegmentKey segmentKey(SessionSeat seat) {
        return new SegmentKey(seat.sourceAreaId(), seat.rowLabelSnapshot(), seat.priceTierId(),
                seat.aisleSegmentSnapshot());
    }

    private Validation accepted() {
        return new Validation(true, "OK", List.of());
    }

    private Validation rejected(String code) {
        return new Validation(false, code, List.of());
    }
}
