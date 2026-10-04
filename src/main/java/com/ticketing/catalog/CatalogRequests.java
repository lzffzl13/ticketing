package com.ticketing.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class CatalogRequests {
    private CatalogRequests() { }

    public record CreateEvent(@NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{0,63}") String code,
                              @NotBlank @Size(max = 128) String name,
                              @Size(max = 2000) String description, @NotNull Boolean enabled) { }

    public record PriceTierInput(@NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{0,63}") String code,
                                 @NotBlank @Size(max = 128) String name,
                                 @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal price) { }

    public record AreaPriceMapping(@NotNull @Positive Long areaId,
                                    @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{0,63}") String priceTierCode) { }

    public record SeatPriceOverride(@NotNull @Positive Long seatTemplateId,
                                     @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{0,63}") String priceTierCode) { }

    public record CreateSession(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{0,63}") String code,
            @NotNull @Positive Long eventId, @NotNull @Positive Long venueId,
            @NotNull Instant startsAt, @NotNull Instant admissionOpenAt, @NotNull Instant admissionCloseAt,
            @NotNull Boolean forbidNewOrphan,
            @NotEmpty List<@Valid PriceTierInput> priceTiers,
            @NotNull List<@Valid AreaPriceMapping> areaPrices,
            @NotNull List<@Valid SeatPriceOverride> seatOverrides) { }
}
