package com.ticketing.trade;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class RefundModels {
    private RefundModels() { }
    public enum RefundStatus { REQUESTED, PROCESSING, SUCCEEDED, FAILED;
        public boolean terminal() { return this == SUCCEEDED || this == FAILED; }
    }
    public record RefundRecord(Long id, String refundNumber, Long customerId, Long orderId,
            Long orderItemId, Long sessionId, Long sessionSeatId, Long ticketId, String ticketNumber,
            Long paymentId, String paymentNumber, BigDecimal amount, String requestKey,
            RefundStatus refundStatus, Instant requestedAt, Instant acceptedAt, Instant resultAt,
            String failureCode, Instant createdAt, Instant updatedAt, Instant firstUnresolvedAt,
            int consecutiveFailures, Instant nextRecoveryAt, String recentError, boolean manualRequired,
            @JsonIgnore String recoveryToken, Instant recoveryClaimUntil) { }
    public record RefundOrderState(Long orderId, TradeModels.OrderStatus orderStatus,
            BigDecimal refundedAmount, List<RefundRecord> refunds) {
        public RefundOrderState { refunds = List.copyOf(refunds); }
    }
    record Work(RefundRecord refund, String token) { }
}
