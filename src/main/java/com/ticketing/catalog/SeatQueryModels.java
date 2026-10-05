package com.ticketing.catalog;

import com.ticketing.catalog.CatalogModels.SalesStatus;
import com.ticketing.inventory.InventoryModels.SeatStatus;
import com.ticketing.inventory.SeatRecommendationModels.Candidate;
import com.ticketing.inventory.SeatRecommendationModels.Diagnostics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class SeatQueryModels {
    private SeatQueryModels() { }

    public record SeatMap(long sessionId, SalesStatus salesStatus, boolean forbidNewOrphan,
                          Instant queriedAt, boolean reservationMade, List<SeatMapArea> areas) { }

    public record SeatMapArea(String code, String name, int displayOrder, List<SeatMapRow> rows) { }

    public record SeatMapRow(String label, int order, List<SeatMapSeat> seats) { }

    /** 库存状态保留原值；selectable 另外综合场次销售状态与票档开关。 */
    public record SeatMapSeat(long id, int seatNumber, BigDecimal coordinateX, BigDecimal coordinateY,
                              String aisleSegment, Long priceTierId, String priceTierName, BigDecimal price,
                              SeatStatus seatStatus, boolean priceTierEnabled, boolean selectable) { }

    public record Recommendations(long sessionId, int quantity, Instant queriedAt, boolean reservationMade,
                                  List<Candidate> candidates, Diagnostics diagnostics) { }
}
