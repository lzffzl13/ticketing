package com.ticketing.simulator;

import java.math.BigDecimal;
import java.time.Instant;

/** Independent channel refund facts and the immutable facts captured in each notification. */
public final class RefundSimulatorModels {
    private RefundSimulatorModels() { }

    public enum Status { PROCESSING, SUCCEEDED, FAILED }

    public record Binding(String refundNumber, String paymentNumber, String merchantOrderNumber,
                          String ticketNumber, BigDecimal amount) { }

    public record Refund(String refundNumber, String paymentNumber, String merchantOrderNumber,
                         String ticketNumber, BigDecimal amount, Status refundStatus,
                         Instant acceptedAt, Instant resultAt, String failureCode) { }

    public record Notification(String notificationNumber, Refund refund,
                               Instant notifiedAt, Instant deliveredAt) { }
}
