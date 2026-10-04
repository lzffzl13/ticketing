package com.ticketing.venue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class VenueModels {
    private VenueModels() { }

    public record Venue(Long id, String code, String name, String address, boolean enabled,
                        Instant createdAt, Instant updatedAt) { }

    public record Area(Long id, Long venueId, String code, String name, int displayOrder,
                       Instant createdAt, Instant updatedAt) { }

    public record SeatTemplate(Long id, Long areaId, String rowLabel, int rowOrder, int seatNumber,
                               BigDecimal coordinateX, BigDecimal coordinateY, String aisleSegment,
                               boolean enabled, Instant createdAt, Instant updatedAt) { }

    /** A source value, read within the caller's snapshot transaction; never a live reference. */
    public record LayoutSeat(Long id, Long areaId, String areaCode, String areaName, int areaDisplayOrder,
                             String rowLabel, int rowOrder, int seatNumber, BigDecimal coordinateX,
                             BigDecimal coordinateY, String aisleSegment, boolean enabled) { }

    public record VenueLayout(Venue venue, List<Area> areas, List<LayoutSeat> seats) { }
}
