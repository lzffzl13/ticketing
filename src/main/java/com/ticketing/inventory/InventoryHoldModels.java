package com.ticketing.inventory;

import java.time.Instant;

public final class InventoryHoldModels {
    private InventoryHoldModels() { }

    public enum HoldStatus { ACTIVE, CONSUMED, RELEASED, EXPIRED }

    public record Hold(Long id, Long orderId, Long sessionId, HoldStatus status,
                       Instant expiresAt, Instant createdAt, Instant updatedAt) { }
}
