package com.ticketing.venue;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public final class VenueRequests {
    private VenueRequests() { }

    public record CreateVenue(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{0,63}") String code,
            @NotBlank @Size(max = 128) String name,
            @Size(max = 255) String address,
            @NotNull Boolean enabled) { }

    public record UpdateVenue(@NotBlank @Size(max = 128) String name,
                              @Size(max = 255) String address, @NotNull Boolean enabled) { }

    public record CreateArea(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{0,63}") String code,
            @NotBlank @Size(max = 128) String name, @NotNull @PositiveOrZero Integer displayOrder) { }

    public record UpdateArea(@NotBlank @Size(max = 128) String name,
                             @NotNull @PositiveOrZero Integer displayOrder) { }

    public record SeatInput(
            @NotBlank @Size(max = 32) String rowLabel,
            @NotNull @PositiveOrZero Integer rowOrder,
            @NotNull @Positive Integer seatNumber,
            @NotNull @Digits(integer = 7, fraction = 3) BigDecimal coordinateX,
            @NotNull @Digits(integer = 7, fraction = 3) BigDecimal coordinateY,
            @NotBlank @Size(max = 32) String aisleSegment,
            @NotNull Boolean enabled) { }

    public record UpdateSeat(@NotNull @Positive Long areaId,
                              @NotBlank @Size(max = 32) String rowLabel,
                              @NotNull @PositiveOrZero Integer rowOrder,
                              @NotNull @Positive Integer seatNumber,
                              @NotNull @Digits(integer = 7, fraction = 3) BigDecimal coordinateX,
                              @NotNull @Digits(integer = 7, fraction = 3) BigDecimal coordinateY,
                              @NotBlank @Size(max = 32) String aisleSegment,
                              @NotNull Boolean enabled) { }
}
