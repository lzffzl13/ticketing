package com.ticketing.consistency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Minimal local read model; every list must come from one nonlocking MySQL snapshot. */
record ConsistencySnapshot(List<Order> orders, List<Item> items, List<Hold> holds, List<HoldItem> holdItems,
                           List<Payment> payments, List<Seat> seats, List<Ticket> tickets,
                           List<Refund> refunds, List<Checkin> checkins) {
    record Session(long id, Instant admissionCloseAt) { }
    record Order(long id, String orderStatus, BigDecimal totalAmount, Instant paymentExpiresAt,
                 boolean manualRequired) { }
    record Item(long id, long orderId, long sessionSeatId, BigDecimal transactionPrice) { }
    record Hold(long id, long orderId, String holdStatus, Instant expiresAt) { }
    record HoldItem(long holdId, long sessionSeatId) { }
    record Payment(long id, long orderId, String paymentStatus, BigDecimal amount, Instant paymentExpiresAt) { }
    record Seat(long id, String seatStatus, Long currentHoldId) { }
    record Ticket(long id, long orderId, long orderItemId, long sessionSeatId, String ticketStatus) { }
    record Refund(long id, long orderId, long orderItemId, long ticketId, long paymentId,
                  String refundStatus, BigDecimal amount, boolean manualRequired) { }
    record Checkin(long id, Long ticketId, Long sessionId, long submittedSessionId) { }
}
