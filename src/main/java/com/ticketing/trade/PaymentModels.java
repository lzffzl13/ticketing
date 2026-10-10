package com.ticketing.trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Local progress is separate from independently committed channel facts. */
public final class PaymentModels {
    private PaymentModels() { }
    public enum PaymentStatus { INITIATED, PROCESSING, SUCCEEDED, FAILED, CLOSED;
        public boolean terminal() { return this == SUCCEEDED || this == FAILED || this == CLOSED; }
    }
    public record PaymentRecord(Long id, Long orderId, Long sessionId, String paymentNumber,
                                BigDecimal amount, Instant paymentExpiresAt, String requestKey,
                                PaymentStatus paymentStatus, Instant acceptedAt, Instant resultAt,
                                String failureCode, Instant createdAt, Instant updatedAt) { }
    public record OrderPaymentState(Long orderId, TradeModels.OrderStatus orderStatus,
                                    Instant cancellationRequestedAt, String cancellationReason,
                                    Instant firstUnresolvedAt, int consecutiveFailures, Instant nextRecoveryAt,
                                    String recentError, boolean manualRequired, Instant recoveryClaimUntil,
                                    List<PaymentRecord> payments) {
        public OrderPaymentState { payments = List.copyOf(payments); }
    }
    // Storage/coordination DTOs never expose a lease token through HTTP.
    record RecoveryOrder(Long id, String orderNumber, Long customerId, Long sessionId,
                         TradeModels.OrderStatus orderStatus, BigDecimal totalAmount, Instant paymentExpiresAt,
                         Instant cancellationRequestedAt, String cancellationReason, Instant firstUnresolvedAt,
                         int consecutiveFailures, Instant nextRecoveryAt, String recentError,
                         boolean manualRequired, String recoveryToken, Instant recoveryClaimUntil) { }
    record Work(RecoveryOrder order, List<PaymentRecord> payments, String token) {
        Work { payments = List.copyOf(payments); }
    }
    record Beginning(PaymentRecord payment, Work work) { }
}
