package com.ticketing.inventory;

import java.math.BigDecimal;
import java.time.Instant;

public final class InventoryModels {
    private InventoryModels() { }

    public enum SeatStatus { UNAVAILABLE, AVAILABLE, HELD, SOLD }

    /** Catalog supplies resolved prices; inventory does not access catalog's Mapper or request types. */
    public record SeatConfiguration(Long id, Long priceTierId, String priceTierName, BigDecimal price,
                                    boolean available) { }

    public record SessionSeat(Long id, Long sessionId, Long sourceSeatTemplateId, Long sourceAreaId,
                              String areaCodeSnapshot, String areaNameSnapshot, int areaDisplayOrderSnapshot,
                              String rowLabelSnapshot, int rowOrderSnapshot, int seatNumberSnapshot,
                              BigDecimal coordinateXSnapshot, BigDecimal coordinateYSnapshot, String aisleSegmentSnapshot,
                              Long priceTierId, String priceTierNameSnapshot, BigDecimal priceSnapshot,
                              SeatStatus seatStatus, Instant createdAt, Instant updatedAt) { }
}
