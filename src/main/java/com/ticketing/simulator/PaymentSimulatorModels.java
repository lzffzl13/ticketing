package com.ticketing.simulator;

import java.math.BigDecimal;
import java.time.Instant;

/** Channel facts and immutable notification snapshots; neither owns local orders or tickets. */
public final class PaymentSimulatorModels {
    private PaymentSimulatorModels() { }

    public enum PaymentStatus { PROCESSING, SUCCEEDED, FAILED, CLOSED }

    public record Binding(String paymentNumber, String merchantOrderNumber, BigDecimal amount,
                          Instant paymentExpiresAt) { }

    public record Payment(String paymentNumber, String merchantOrderNumber, BigDecimal amount,
                          Instant paymentExpiresAt, PaymentStatus paymentStatus, Instant acceptedAt,
                          Instant resultAt, String failureCode, Instant createdAt, Instant updatedAt) { }

    public record Notification(String notificationNumber, String paymentNumber, PaymentStatus observedStatus,
                               Instant observedAt, Instant deliveredAt) { }
}
