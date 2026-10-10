package com.ticketing.inventory;

import java.time.Instant;

public final class InventoryHoldModels {
    private InventoryHoldModels() { }

    public enum HoldStatus { ACTIVE, CONSUMED, RELEASED, EXPIRED }

    public record Hold(Long id, Long orderId, Long sessionId, HoldStatus status,
                       Instant expiresAt, Instant createdAt, Instant updatedAt) { }

    /** Write-side ownership read after locking this individual seat. */
    record LockedSeat(Long id, Long sessionId, InventoryModels.SeatStatus seatStatus, Long currentHoldId) { }
}
