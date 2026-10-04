package com.ticketing.catalog;

import java.math.BigDecimal;
import java.time.Instant;

public final class CatalogModels {
    private CatalogModels() { }

    public enum SalesStatus { DRAFT, ON_SALE, STOPPED }

    public record Event(Long id, String code, String name, String description, boolean enabled,
                        Instant createdAt, Instant updatedAt) { }

    public record Session(Long id, String code, Long eventId, Long venueId, String eventNameSnapshot,
                          String venueNameSnapshot, Instant startsAt, Instant admissionOpenAt,
                          Instant admissionCloseAt, SalesStatus salesStatus, Instant firstOpenedAt,
                          boolean forbidNewOrphan, Instant createdAt, Instant updatedAt) { }

    public record PriceTier(Long id, Long sessionId, String code, String name, BigDecimal price, boolean enabled,
                            Instant createdAt, Instant updatedAt) { }

    public record CreatedSession(Long id, String code, int seatCount, int availableSeatCount, int unavailableSeatCount) { }
}
