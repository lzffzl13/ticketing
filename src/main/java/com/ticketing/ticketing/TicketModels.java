package com.ticketing.ticketing;

import java.time.Instant;

public final class TicketModels {
    private TicketModels() { }

    public enum TicketStatus { VALID, REFUNDING, REFUNDED, CHECKED_IN }

    public record Ticket(Long id, String ticketNumber, Long orderItemId, Long orderId, Long sessionId,
                         Long sessionSeatId, TicketStatus ticketStatus, Instant issuedAt) { }

    public record IssueItem(Long orderItemId, Long sessionSeatId) { }
}
