package com.ticketing.simulator;

import com.ticketing.common.web.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.ticketing.simulator.PaymentSimulatorModels.*;
import static com.ticketing.simulator.SimulatorStorage.*;

/** Every entry runs through this separate proxy and commits only simulator facts. */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
public class PaymentSimulatorTransactionService {
    private final PaymentSimulatorMapper mapper;
    private final Clock clock;

    public PaymentSimulatorTransactionService(PaymentSimulatorMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public Payment open(Binding binding, boolean close) {
        var existing = mapper.lockPayment(binding.paymentNumber());
        if (existing != null) {
            requireBinding(existing, binding);
            if (close && existing.paymentStatus() == StoredStatus.PROCESSING) {
                return finishLocked(existing, StoredStatus.CLOSED, null);
            }
            return existing.external();
        }

        // A missing row cannot serialize first callers. The unique INSERT claims this number.
        // DuplicateKeyException must leave this proxy before the facade re-reads in a new transaction.
        requireOne(mapper.insertReserved(binding, now()), "Simulator number was not reserved");
        var at = now(); // Only this lock-owner's time decides first acceptance, never a pre-wait clock.
        var status = close ? StoredStatus.CLOSED
                : at.isBefore(binding.paymentExpiresAt()) ? StoredStatus.PROCESSING : StoredStatus.FAILED;
        var acceptedAt = status == StoredStatus.PROCESSING ? at : null;
        var resultAt = status == StoredStatus.PROCESSING ? null : at;
        var failureCode = status == StoredStatus.FAILED ? "EXPIRED_BEFORE_ACCEPTANCE" : null;
        requireOne(mapper.initializePayment(binding.paymentNumber(), status, acceptedAt, resultAt, failureCode, at),
                "Simulator reservation was not initialized");
        appendNotification(binding.paymentNumber(), PaymentStatus.valueOf(status.name()), at);
        return requirePayment(mapper.findPayment(binding.paymentNumber())).external();
    }

    public Payment finish(String paymentNumber, PaymentStatus target) {
        var stored = requirePayment(mapper.lockPayment(paymentNumber));
        var wanted = StoredStatus.valueOf(target.name());
        if (stored.paymentStatus() == wanted) { return stored.external(); }
        if (stored.paymentStatus() != StoredStatus.PROCESSING) {
            // Includes RESERVED: corrupted/incomplete facts must never become a proof of non-payment.
            stored.external();
            throw new BusinessException("SIMULATOR_STATE_CONFLICT", "模拟交易已终结，不能改写为另一个结果",
                    HttpStatus.CONFLICT);
        }
        return finishLocked(stored, wanted, target == PaymentStatus.FAILED ? "SIMULATED_FAILURE" : null);
    }

    public Notification createNotification(String paymentNumber) {
        var payment = requirePayment(mapper.lockPayment(paymentNumber)).external();
        return appendNotification(paymentNumber, payment.paymentStatus(), now());
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Optional<Payment> find(String paymentNumber) {
        return Optional.ofNullable(mapper.findPayment(paymentNumber)).map(StoredPayment::external);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public List<Notification> notifications(String paymentNumber) {
        requirePayment(mapper.findPayment(paymentNumber)).external();
        return List.copyOf(mapper.notifications(paymentNumber));
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Notification notification(String notificationNumber) {
        return requireNotification(mapper.notification(notificationNumber));
    }

    public void acknowledge(String notificationNumber) {
        var notification = requireNotification(mapper.notification(notificationNumber));
        if (notification.deliveredAt() != null) { return; }
        int changed = mapper.acknowledgeNotification(notificationNumber, now());
        // Another delivery can commit first. Both receivers have already successfully committed.
        if (changed != 1 && requireNotification(mapper.notification(notificationNumber)).deliveredAt() == null) {
            throw new IllegalStateException("Simulator delivery was not acknowledged");
        }
    }

    private Payment finishLocked(StoredPayment stored, StoredStatus status, String failureCode) {
        var at = now();
        // Audit times must remain ordered even if the wall clock steps backwards after acceptance.
        if (at.isBefore(stored.acceptedAt())) { at = stored.acceptedAt(); }
        requireOne(mapper.finishPayment(stored.paymentNumber(), status, at, failureCode),
                "Simulator processing state changed unexpectedly");
        appendNotification(stored.paymentNumber(), PaymentStatus.valueOf(status.name()), at);
        return requirePayment(mapper.findPayment(stored.paymentNumber())).external();
    }

    private Notification appendNotification(String number, PaymentStatus status, Instant at) {
        var notification = new Notification(UUID.randomUUID().toString(), number, status, at, null);
        requireOne(mapper.insertNotification(notification), "Simulator notification was not saved");
        return notification;
    }

    private void requireBinding(StoredPayment payment, Binding binding) {
        if (!payment.merchantOrderNumber().equals(binding.merchantOrderNumber())
                || payment.amount().compareTo(binding.amount()) != 0
                || !payment.paymentExpiresAt().equals(binding.paymentExpiresAt())) {
            throw new BusinessException("SIMULATOR_BINDING_CONFLICT", "支付编号已绑定其他订单、金额或截止时间",
                    HttpStatus.CONFLICT);
        }
    }

    private StoredPayment requirePayment(StoredPayment payment) {
        if (payment == null) { throw notFound("模拟支付交易不存在"); }
        return payment;
    }

    private Notification requireNotification(Notification notification) {
        if (notification == null) { throw notFound("模拟支付通知不存在"); }
        return notification;
    }

    private BusinessException notFound(String message) {
        return new BusinessException("NOT_FOUND", message, HttpStatus.NOT_FOUND);
    }

    private void requireOne(int changed, String message) {
        if (changed != 1) { throw new IllegalStateException(message); }
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
