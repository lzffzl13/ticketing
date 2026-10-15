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

import static com.ticketing.simulator.RefundSimulatorModels.*;
import static com.ticketing.simulator.RefundSimulatorStorage.*;

/** Separate proxy boundaries commit only channel facts, never local refund, ticket or inventory rows. */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
public class RefundSimulatorTransactionService {
    private final RefundSimulatorMapper mapper;
    private final Clock clock;

    public RefundSimulatorTransactionService(RefundSimulatorMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public Refund start(Binding binding) {
        // Every channel writer takes the original payment lock before any refund lock.
        // An existing number's immutable binding locates that original lock even if a replay supplies
        // a different, nonexistent payment. The locked refund below still decides the binding check.
        var observed = mapper.findRefund(binding.refundNumber());
        var paymentNumber = observed == null ? binding.paymentNumber() : observed.paymentNumber();
        var payment = requirePayment(mapper.lockPayment(paymentNumber));
        var existing = mapper.lockRefund(binding.refundNumber());
        if (existing != null) {
            requireBinding(existing, binding);
            return existing;
        }
        if (!payment.merchantOrderNumber().equals(binding.merchantOrderNumber())) {
            throw bindingConflict();
        }
        if (!"SUCCEEDED".equals(payment.paymentStatus())) {
            throw conflict("SIMULATOR_STATE_CONFLICT", "原模拟支付尚未成功，不能退款");
        }
        var reserved = mapper.reservedAmount(binding.paymentNumber());
        if (reserved.add(binding.amount()).compareTo(payment.amount()) > 0) {
            throw conflict("SIMULATOR_REFUND_LIMIT_EXCEEDED", "进行中及成功退款总额不能超过原收款");
        }
        var at = now();
        requireOne(mapper.insertRefund(binding, at), "Simulator refund was not saved");
        var refund = requireRefund(mapper.findRefund(binding.refundNumber()));
        appendNotification(refund, at);
        return refund;
    }

    public Refund finish(String refundNumber, Status target) {
        // An unlocked lookup locates the immutable payment binding; it does not claim the refund.
        var observed = requireRefund(mapper.findRefund(refundNumber));
        requirePayment(mapper.lockPayment(observed.paymentNumber()));
        var refund = requireRefund(mapper.lockRefund(refundNumber));
        if (refund.refundStatus() == target) { return refund; }
        if (refund.refundStatus() != Status.PROCESSING) {
            throw conflict("SIMULATOR_STATE_CONFLICT", "模拟退款已终结，不能改写为另一个结果");
        }
        var at = now();
        if (at.isBefore(refund.acceptedAt())) { at = refund.acceptedAt(); }
        var failureCode = target == Status.FAILED ? "SIMULATED_FAILURE" : null;
        requireOne(mapper.finishRefund(refundNumber, target, at, failureCode),
                "Simulator refund processing state changed unexpectedly");
        var finished = requireRefund(mapper.findRefund(refundNumber));
        appendNotification(finished, at);
        return finished;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Optional<Refund> find(String refundNumber) {
        return Optional.ofNullable(mapper.findRefund(refundNumber));
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public List<Notification> notifications(String refundNumber) {
        requireRefund(mapper.findRefund(refundNumber));
        return mapper.notifications(refundNumber).stream().map(StoredNotification::external).toList();
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Notification notification(String notificationNumber) {
        return requireNotification(mapper.notification(notificationNumber)).external();
    }

    public void acknowledge(String notificationNumber) {
        var notification = requireNotification(mapper.notification(notificationNumber));
        if (notification.deliveredAt() != null) { return; }
        int changed = mapper.acknowledgeNotification(notificationNumber, now());
        if (changed != 1 && requireNotification(mapper.notification(notificationNumber)).deliveredAt() == null) {
            throw new IllegalStateException("Simulator refund notification was not acknowledged");
        }
    }

    private void appendNotification(Refund refund, Instant at) {
        var notification = new Notification(UUID.randomUUID().toString(), refund, at, null);
        requireOne(mapper.insertNotification(notification), "Simulator refund notification was not saved");
    }

    private void requireBinding(Refund refund, Binding binding) {
        if (!refund.paymentNumber().equals(binding.paymentNumber())
                || !refund.merchantOrderNumber().equals(binding.merchantOrderNumber())
                || !refund.ticketNumber().equals(binding.ticketNumber())
                || refund.amount().compareTo(binding.amount()) != 0) {
            throw bindingConflict();
        }
    }

    private ChannelPayment requirePayment(ChannelPayment payment) {
        if (payment == null) { throw notFound("原模拟支付不存在"); }
        return payment;
    }

    private Refund requireRefund(Refund refund) {
        if (refund == null) { throw notFound("模拟退款不存在"); }
        return refund;
    }

    private StoredNotification requireNotification(StoredNotification notification) {
        if (notification == null) { throw notFound("模拟退款通知不存在"); }
        return notification;
    }

    private BusinessException bindingConflict() {
        return conflict("SIMULATOR_BINDING_CONFLICT", "退款编号已绑定其他原支付、订单、票或金额");
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(code, message, HttpStatus.CONFLICT);
    }

    private BusinessException notFound(String message) {
        return new BusinessException("NOT_FOUND", message, HttpStatus.NOT_FOUND);
    }

    private void requireOne(int changed, String message) {
        if (changed != 1) { throw new IllegalStateException(message); }
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
