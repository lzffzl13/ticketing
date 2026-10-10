package com.ticketing.simulator;

import java.math.BigDecimal;
import java.time.Instant;

import static com.ticketing.simulator.PaymentSimulatorModels.*;

/** RESERVED exists only while the transaction owns a newly inserted number. */
final class SimulatorStorage {
    private SimulatorStorage() { }

    enum StoredStatus { RESERVED, PROCESSING, SUCCEEDED, FAILED, CLOSED }

    record StoredPayment(String paymentNumber, String merchantOrderNumber, BigDecimal amount,
                         Instant paymentExpiresAt, StoredStatus paymentStatus, Instant acceptedAt,
                         Instant resultAt, String failureCode, Instant createdAt, Instant updatedAt) {
        Payment external() {
            if (paymentStatus == StoredStatus.RESERVED) {
                throw new IllegalStateException("An uninitialized simulator reservation must not be published");
            }
            return new Payment(paymentNumber, merchantOrderNumber, amount, paymentExpiresAt,
                    PaymentStatus.valueOf(paymentStatus.name()), acceptedAt, resultAt, failureCode,
                    createdAt, updatedAt);
        }
    }
}
