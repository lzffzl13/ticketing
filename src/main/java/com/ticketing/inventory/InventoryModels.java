package com.ticketing.inventory;

import java.math.BigDecimal;
import java.time.Instant;

public final class InventoryModels {
    private InventoryModels() { }

    public enum SeatStatus { UNAVAILABLE, AVAILABLE, HELD, SOLD }

    public record SessionSeat(Long id, Long sessionId, Long sourceSeatTemplateId, Long sourceAreaId,
                              String areaCodeSnapshot, String areaNameSnapshot, int areaDisplayOrderSnapshot,
                              String rowLabelSnapshot, int rowOrderSnapshot, int seatNumberSnapshot,
                              BigDecimal coordinateXSnapshot, BigDecimal coordinateYSnapshot, String aisleSegmentSnapshot,
                              Long priceTierId, String priceTierNameSnapshot, BigDecimal priceSnapshot,
                              SeatStatus seatStatus, Instant createdAt, Instant updatedAt) { }
}
