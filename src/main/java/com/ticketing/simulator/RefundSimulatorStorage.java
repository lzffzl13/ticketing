package com.ticketing.simulator;

import java.math.BigDecimal;
import java.time.Instant;

import static com.ticketing.simulator.RefundSimulatorModels.*;

/** Flat persistence records let MyBatis construct a notification without consulting mutable facts. */
final class RefundSimulatorStorage {
    private RefundSimulatorStorage() { }

    record ChannelPayment(String paymentNumber, String merchantOrderNumber,
                          BigDecimal amount, String paymentStatus) { }

    record StoredNotification(String notificationNumber, String refundNumber, String paymentNumber,
                              String merchantOrderNumber, String ticketNumber, BigDecimal amount,
                              Status refundStatus, Instant acceptedAt, Instant resultAt,
                              String failureCode, Instant notifiedAt, Instant deliveredAt) {
        Notification external() {
            var refund = new Refund(refundNumber, paymentNumber, merchantOrderNumber, ticketNumber,
                    amount, refundStatus, acceptedAt, resultAt, failureCode);
            return new Notification(notificationNumber, refund, notifiedAt, deliveredAt);
        }
    }
}
