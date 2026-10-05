package com.ticketing.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.ticketing.inventory.InventoryModels.SessionSeat;
import com.ticketing.inventory.SeatRecommendationModels.Candidate;
import com.ticketing.inventory.SeatRecommendationModels.Diagnostics;
import com.ticketing.inventory.SeatRecommendationModels.Result;

/** Read-only deterministic scan; candidates are proposals, never seat reservations. */
public final class SeatRecommendationEngine {
    // Repeating decimal averages have a documented, input-order-independent precision.
    public static final int CENTER_SCALE = 12;
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final String BASE_REASON = "同区域、同排、同票档、座号连续且不跨过道；按区域展示顺序及区域中心距离排序";
    private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator
            .comparingInt((Candidate candidate) -> first(candidate).areaDisplayOrderSnapshot())
            .thenComparing(candidate -> first(candidate).areaCodeSnapshot())
            .thenComparing(candidate -> first(candidate).sourceAreaId())
            .thenComparing(Candidate::centerDistanceSquared)
            .thenComparingInt(candidate -> first(candidate).rowOrderSnapshot())
            .thenComparing(candidate -> first(candidate).rowLabelSnapshot())
            .thenComparingInt(candidate -> first(candidate).seatNumberSnapshot())
            .thenComparing(candidate -> first(candidate).id());

    private final SeatSelectionRules selectionRules;

    public SeatRecommendationEngine() {
        this(new SeatSelectionRules());
    }

    public SeatRecommendationEngine(SeatSelectionRules selectionRules) {
        this.selectionRules = Objects.requireNonNull(selectionRules, "selectionRules");
    }

    public Result recommend(List<SessionSeat> fullLayout, Set<Long> enabledTierIds, String areaCode,
                            Long priceTierId, int quantity, int limit, boolean forbidNewOrphan) {
        Objects.requireNonNull(fullLayout, "fullLayout");
        Objects.requireNonNull(enabledTierIds, "enabledTierIds");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (limit < 1 || limit > 20) {
            throw new IllegalArgumentException("limit must be between 1 and 20");
        }

        Map<AreaKey, Bounds> areaBounds = areaBounds(fullLayout);
        List<Candidate> candidates = new ArrayList<>();
        int availableSeatCount = 0;
        int contiguousRunCount = 0;
        int tooShortRunCount = 0;
        int newOrphanRejectedCount = 0;

        // Split the entire layout first: filtering earlier could join seats across a boundary.
        for (List<SessionSeat> run : selectionRules.availableRuns(fullLayout)) {
            SessionSeat first = run.getFirst();
            if (first.priceTierId() == null || !enabledTierIds.contains(first.priceTierId())
                    || (areaCode != null && !areaCode.equals(first.areaCodeSnapshot()))
                    || (priceTierId != null && !priceTierId.equals(first.priceTierId()))) {
                continue;
            }
            availableSeatCount += run.size();
            contiguousRunCount++;
            if (run.size() < quantity) {
                tooShortRunCount++;
                continue;
            }

            Bounds bounds = areaBounds.get(AreaKey.of(first));
            for (int start = 0; start <= run.size() - quantity; start++) {
                if (forbidNewOrphan && selectionRules.createsNewOrphan(run.size(), start, quantity)) {
                    newOrphanRejectedCount++;
                    continue;
                }
                candidates.add(candidate(run.subList(start, start + quantity), bounds, forbidNewOrphan));
            }
        }

        candidates.sort(CANDIDATE_ORDER);
        Diagnostics diagnostics = new Diagnostics(availableSeatCount, contiguousRunCount, tooShortRunCount,
                newOrphanRejectedCount, candidates.size());
        return new Result(candidates.subList(0, Math.min(limit, candidates.size())), diagnostics);
    }

    private static Candidate candidate(List<SessionSeat> seats, Bounds bounds, boolean forbidNewOrphan) {
        BigDecimal totalPrice = BigDecimal.ZERO;
        BigDecimal sumX = BigDecimal.ZERO;
        BigDecimal sumY = BigDecimal.ZERO;
        for (SessionSeat seat : seats) {
            totalPrice = totalPrice.add(seat.priceSnapshot());
            sumX = sumX.add(seat.coordinateXSnapshot());
            sumY = sumY.add(seat.coordinateYSnapshot());
        }
        BigDecimal quantity = BigDecimal.valueOf(seats.size());
        BigDecimal deltaX = sumX.divide(quantity, CENTER_SCALE, RoundingMode.HALF_UP).subtract(bounds.centerX());
        BigDecimal deltaY = sumY.divide(quantity, CENTER_SCALE, RoundingMode.HALF_UP).subtract(bounds.centerY());
        BigDecimal distanceSquared = deltaX.multiply(deltaX).add(deltaY.multiply(deltaY));
        String reason = BASE_REASON + (forbidNewOrphan ? "；未制造新孤座" : "；场次允许制造孤座");
        return new Candidate(seats, totalPrice, distanceSquared, reason);
    }

    private static Map<AreaKey, Bounds> areaBounds(List<SessionSeat> fullLayout) {
        Map<AreaKey, Bounds> bounds = new HashMap<>();
        for (SessionSeat seat : fullLayout) {
            bounds.compute(AreaKey.of(seat), (key, current) -> current == null
                    ? Bounds.of(seat) : current.include(seat));
        }
        return bounds;
    }

    private static SessionSeat first(Candidate candidate) {
        return candidate.seats().getFirst();
    }

    private record AreaKey(Long sourceAreaId, String areaCode) {
        static AreaKey of(SessionSeat seat) {
            return new AreaKey(seat.sourceAreaId(), seat.areaCodeSnapshot());
        }
    }

    private record Bounds(BigDecimal minX, BigDecimal maxX, BigDecimal minY, BigDecimal maxY) {
        static Bounds of(SessionSeat seat) {
            return new Bounds(seat.coordinateXSnapshot(), seat.coordinateXSnapshot(),
                    seat.coordinateYSnapshot(), seat.coordinateYSnapshot());
        }

        Bounds include(SessionSeat seat) {
            return new Bounds(minX.min(seat.coordinateXSnapshot()), maxX.max(seat.coordinateXSnapshot()),
                    minY.min(seat.coordinateYSnapshot()), maxY.max(seat.coordinateYSnapshot()));
        }

        BigDecimal centerX() {
            return minX.add(maxX).divide(TWO);
        }

        BigDecimal centerY() {
            return minY.add(maxY).divide(TWO);
        }
    }
}
