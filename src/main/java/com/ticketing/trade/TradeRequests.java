package com.ticketing.trade;

import com.ticketing.trade.TradeModels.SelectionMode;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public final class TradeRequests {
    private TradeRequests() { }

    public record CreateOrder(@Positive long sessionId, @NotNull SelectionMode selectionMode,
                              @NotEmpty List<@NotNull @Positive Long> seatIds) { }
}
