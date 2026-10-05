package com.ticketing.inventory;

import java.math.BigDecimal;
import java.util.List;

import com.ticketing.inventory.InventoryModels.SessionSeat;

public final class SeatRecommendationModels {
    private SeatRecommendationModels() { }

    public record Candidate(List<SessionSeat> seats, BigDecimal totalPrice,
                            BigDecimal centerDistanceSquared, String reason) {
        public Candidate {
            seats = List.copyOf(seats);
        }
    }

    public record Diagnostics(int availableSeatCount, int contiguousRunCount, int tooShortRunCount,
                              int newOrphanRejectedCount, int validCandidateCount) { }

    public record Result(List<Candidate> candidates, Diagnostics diagnostics) {
        public Result {
            candidates = List.copyOf(candidates);
        }
    }
}
