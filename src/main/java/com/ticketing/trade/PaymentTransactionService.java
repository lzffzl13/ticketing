package com.ticketing.trade;

import com.ticketing.catalog.CatalogPurchaseService;
import com.ticketing.common.persistence.GeneratedKey;
import com.ticketing.common.web.BusinessException;
import com.ticketing.inventory.InventoryHoldService;
import com.ticketing.simulator.PaymentSimulatorModels;
import com.ticketing.ticketing.TicketModels.IssueItem;
import com.ticketing.ticketing.TicketService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import static com.ticketing.trade.PaymentModels.*;
import static com.ticketing.trade.TradeModels.OrderStatus.*;

/** Short local transactions. Channel calls never run while this service holds inventory locks. */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
public class PaymentTransactionService {
    private final PaymentMapper mapper;
    private final TradeMapper orders;
    private final CatalogPurchaseService catalog;
    private final InventoryHoldService inventory;
    private final TicketService tickets;
    private final Clock clock;

    public PaymentTransactionService(PaymentMapper mapper, TradeMapper orders, CatalogPurchaseService catalog,
                                     InventoryHoldService inventory, TicketService tickets, Clock clock) {
        this.mapper = mapper; this.orders = orders; this.catalog = catalog;
        this.inventory = inventory; this.tickets = tickets; this.clock = clock;
    }

    public Beginning begin(long customerId, long orderId, String key) {
        var order = lock(orderId, customerId);
        var history = mapper.lockPayments(orderId);
        var original = history.stream().filter(p -> p.requestKey().equals(key)).findFirst();
        if (original.isPresent()) { return new Beginning(original.get(), null); }
        var at = now();
        if (order.orderStatus() != PENDING_PAYMENT || order.cancellationRequestedAt() != null) {
            throw conflict("ORDER_NOT_PAYABLE", "订单已支付、已取消或正在取消，不能发起新支付");
        }
        if (!at.isBefore(order.paymentExpiresAt())) {
            throw conflict("PAYMENT_EXPIRED", "已到原支付截止，不能发起新的支付");
        }
        if (history.stream().anyMatch(p -> !p.paymentStatus().terminal() || p.paymentStatus() == PaymentStatus.SUCCEEDED)) {
            throw conflict("PAYMENT_IN_PROGRESS", "已有进行中或成功支付，请查询原支付");
        }
        var data = new PaymentRecord(null, order.id(), order.sessionId(), UUID.randomUUID().toString(),
                order.totalAmount(), order.paymentExpiresAt(), key, PaymentStatus.INITIATED,
                null, null, null, at, at);
        var generated = new GeneratedKey();
        requireOne(mapper.insertPayment(data, generated));
        var payment = mapper.byNumber(data.paymentNumber());
        // A new attempt supersedes any unfinished recovery of a previous, explicitly failed attempt.
        var token = UUID.randomUUID().toString();
        mapper.updateRecovery(orderId, token, at.plus(RecoveryPolicy.LEASE), null, 0, at, null, false, at);
        return new Beginning(payment, new Work(mapper.orderState(orderId), List.of(payment), token));
    }

    public Work cancel(long customerId, long orderId) {
        var order = lock(orderId, customerId);
        var history = mapper.lockPayments(orderId);
        if (order.orderStatus() != PENDING_PAYMENT) { return null; }
        var at = now();
        mapper.requestCancellation(orderId, "USER", at);
        order = mapper.orderState(orderId);
        if (cancelIfSafe(order, history, at)) { return null; }
        return claimLocked(order, history, at, true);
    }

    public Work claim(long orderId, boolean manual) {
        var order = lock(orderId, null);
        var history = mapper.lockPayments(orderId);
        if (order.orderStatus() != PENDING_PAYMENT) { return null; }
        var at = now();
        // A scan's earlier SELECT is only a hint; recheck before cancellation or release can write anything.
        if (!manual && !automaticallyDue(order, at)) { return null; }
        if (!at.isBefore(order.paymentExpiresAt())) {
            mapper.requestCancellation(orderId, "TIMEOUT", at);
            order = mapper.orderState(orderId);
        }
        // Account for an abandoned lease and the manual threshold before any automatic safe cancellation.
        var work = claimLocked(order, history, at, manual);
        if (work == null) { return null; }
        if (order.cancellationRequestedAt() != null && cancelIfSafe(work.order(), history, at)) { return null; }
        return work;
    }

    private boolean automaticallyDue(RecoveryOrder order, Instant at) {
        if (order.manualRequired() ||
                (order.recoveryToken() != null && at.isBefore(order.recoveryClaimUntil()))) { return false; }
        if (order.nextRecoveryAt() != null) { return !at.isBefore(order.nextRecoveryAt()); }
        return !at.isBefore(order.paymentExpiresAt()) || order.cancellationRequestedAt() != null;
    }

    private Work claimLocked(RecoveryOrder order, List<PaymentRecord> history, Instant at, boolean manual) {
        if (order.recoveryToken() != null && at.isBefore(order.recoveryClaimUntil())) { return null; }
        if (!manual && (order.manualRequired() ||
                (order.nextRecoveryAt() != null && at.isBefore(order.nextRecoveryAt())))) { return null; }
        int failures = order.consecutiveFailures();
        Instant first = order.firstUnresolvedAt();
        String error = order.recentError();
        boolean needsManual = order.manualRequired();
        if (order.recoveryToken() != null) {
            // A crashed worker cannot settle its token; takeover records this interruption exactly once.
            failures++;
            if (first == null) { first = order.recoveryClaimUntil(); }
            error = "RECOVERY_LEASE_EXPIRED";
            needsManual |= RecoveryPolicy.requiresManual(failures, first, at);
            if (!manual && needsManual) {
                mapper.updateRecovery(order.id(), null, null, first, failures,
                        at.plus(RecoveryPolicy.backoff(failures)), error, true, at);
                return null;
            }
        }
        var token = UUID.randomUUID().toString();
        mapper.updateRecovery(order.id(), token, at.plus(RecoveryPolicy.LEASE), first, failures,
                at, error, needsManual, at);
        return new Work(mapper.orderState(order.id()), history, token);
    }

    public void markTimeout(long orderId) {
        var order = lock(orderId, null);
        if (order.orderStatus() == PENDING_PAYMENT && !now().isBefore(order.paymentExpiresAt())) {
            mapper.requestCancellation(orderId, "TIMEOUT", now());
        }
    }

    /** Notifications and queries share this committed, idempotent entry, regardless of recovery ownership. */
    public PaymentRecord applyChannel(PaymentSimulatorModels.Payment channel) {
        var located = mapper.byNumber(channel.paymentNumber());
        if (located == null) { throw notFound(); }
        var order = lock(located.orderId(), null);
        var history = mapper.lockPayments(order.id());
        var local = history.stream().filter(p -> p.paymentNumber().equals(channel.paymentNumber())).findFirst().orElseThrow();
        requireBinding(order, local, channel);
        var target = PaymentStatus.valueOf(channel.paymentStatus().name());
        if (local.paymentStatus().terminal()) {
            if (target == PaymentStatus.PROCESSING || target == local.paymentStatus()) { return local; }
            throw conflict("PAYMENT_FACT_CONFLICT", "本地与渠道的支付终态冲突，需要人工核查");
        }
        if (order.orderStatus() != PENDING_PAYMENT) {
            throw conflict("PAYMENT_FACT_CONFLICT", "未完成支付关联了已经结束的订单");
        }
        var at = now();
        if (channel.resultAt() != null && at.isBefore(channel.resultAt())) { at = channel.resultAt(); }
        var updated = new PaymentRecord(local.id(), local.orderId(), local.sessionId(), local.paymentNumber(),
                local.amount(), local.paymentExpiresAt(), local.requestKey(), target, channel.acceptedAt(),
                channel.resultAt(), channel.failureCode(), local.createdAt(), at);
        requireOne(mapper.applyPayment(updated));
        if (target == PaymentStatus.SUCCEEDED) {
            var items = orders.items(order.id());
            if (items.isEmpty()) { throw new IllegalStateException("Order items missing"); }
            inventory.consume(order.id(), order.sessionId(), items.stream().map(TradeModels.OrderItem::sessionSeatId).toList(), at);
            tickets.issue(order.id(), order.sessionId(), items.stream()
                    .map(item -> new IssueItem(item.id(), item.sessionSeatId())).toList(), at);
            requireOne(mapper.finishOrder(order.id(), PAID, at));
            clearRecovery(order.id(), at);
        } else if (order.cancellationRequestedAt() != null && target.terminal()) {
            cancelIfSafe(order, mapper.lockPayments(order.id()), at);
        }
        return updated;
    }

    public void complete(long orderId, String token) {
        var order = lock(orderId, null);
        var history = mapper.lockPayments(orderId);
        if (!Objects.equals(order.recoveryToken(), token) || token == null) { return; }
        var at = now();
        if (order.orderStatus() != PENDING_PAYMENT) { clearRecovery(orderId, at); return; }
        if (!at.isBefore(order.paymentExpiresAt())) {
            mapper.requestCancellation(orderId, "TIMEOUT", at);
            order = mapper.orderState(orderId);
        }
        if (order.cancellationRequestedAt() != null && cancelIfSafe(order, history, at)) { return; }
        var active = history.stream().anyMatch(p -> !p.paymentStatus().terminal());
        var next = active ? earlier(at.plusSeconds(5), order.paymentExpiresAt()) : order.paymentExpiresAt();
        if (order.cancellationRequestedAt() != null) { next = at; }
        // A complete healthy interaction resolves the actual fault, even if payment is normally PROCESSING.
        mapper.updateRecovery(orderId, null, null, null, 0, next, null, false, at);
    }

    public void fail(long orderId, String token, String error) {
        var order = lock(orderId, null);
        if (!Objects.equals(order.recoveryToken(), token) || token == null || order.orderStatus() != PENDING_PAYMENT) { return; }
        var at = now();
        var first = order.firstUnresolvedAt() == null ? at : order.firstUnresolvedAt();
        var failures = order.consecutiveFailures() + 1;
        boolean manual = "PAYMENT_FACT_CONFLICT".equals(error) || RecoveryPolicy.requiresManual(failures, first, at);
        mapper.updateRecovery(orderId, null, null, first, failures,
                at.plus(RecoveryPolicy.backoff(failures)), error, manual, at);
    }

    public void releaseClaim(long orderId, String token) {
        var order = lock(orderId, null);
        if (token == null || !Objects.equals(order.recoveryToken(), token)) { return; }
        mapper.updateRecovery(orderId, null, null, order.firstUnresolvedAt(), order.consecutiveFailures(),
                order.nextRecoveryAt(), order.recentError(), order.manualRequired(), now());
    }

    public void markConflict(long orderId) {
        var order = lock(orderId, null);
        var at = now();
        mapper.updateRecovery(orderId, order.recoveryToken(), order.recoveryClaimUntil(),
                order.firstUnresolvedAt() == null ? at : order.firstUnresolvedAt(), order.consecutiveFailures(),
                order.nextRecoveryAt(), "PAYMENT_FACT_CONFLICT", true, at);
    }

    /** An unclaimed notification may apply facts, but cannot count a failure owned by another worker. */
    public void noteResultFailure(long orderId, String error) {
        var order = lock(orderId, null);
        if (order.orderStatus() != PENDING_PAYMENT) { return; }
        var at = now();
        var first = order.firstUnresolvedAt() == null ? at : order.firstUnresolvedAt();
        var next = order.nextRecoveryAt() == null ? at.plusSeconds(5) : earlier(order.nextRecoveryAt(), at.plusSeconds(5));
        if ("PAYMENT_FACT_CONFLICT".equals(order.recentError())) { error = order.recentError(); }
        mapper.updateRecovery(orderId, order.recoveryToken(), order.recoveryClaimUntil(), first,
                order.consecutiveFailures(), next, error,
                order.manualRequired() || "PAYMENT_FACT_CONFLICT".equals(error)
                        || RecoveryPolicy.requiresManual(order.consecutiveFailures(), first, at), at);
    }

    private boolean cancelIfSafe(RecoveryOrder order, List<PaymentRecord> history, Instant at) {
        if (order.cancellationRequestedAt() == null || history.stream().anyMatch(p ->
                p.paymentStatus() != PaymentStatus.FAILED && p.paymentStatus() != PaymentStatus.CLOSED)) { return false; }
        var items = orders.items(order.id());
        if (items.isEmpty()) { throw new IllegalStateException("Order items missing"); }
        inventory.release(order.id(), order.sessionId(), items.stream().map(TradeModels.OrderItem::sessionSeatId).toList(),
                "TIMEOUT".equals(order.cancellationReason()), at);
        requireOne(mapper.finishOrder(order.id(), CANCELLED, at));
        clearRecovery(order.id(), at);
        return true;
    }

    private RecoveryOrder lock(long orderId, Long customerId) {
        var located = mapper.orderState(orderId);
        if (located == null || (customerId != null && !customerId.equals(located.customerId()))) { throw notFound(); }
        catalog.lockForPurchase(located.sessionId()); // Existing result processing does not recheck new-sale eligibility.
        var current = mapper.lockOrder(orderId);
        if (current == null || (customerId != null && !customerId.equals(current.customerId()))) { throw notFound(); }
        return current;
    }

    private void requireBinding(RecoveryOrder order, PaymentRecord local, PaymentSimulatorModels.Payment channel) {
        boolean valid = channel.merchantOrderNumber().equals(order.orderNumber())
                && local.amount().compareTo(channel.amount()) == 0 && local.amount().compareTo(order.totalAmount()) == 0
                && local.paymentExpiresAt().equals(channel.paymentExpiresAt())
                && local.paymentExpiresAt().equals(order.paymentExpiresAt());
        boolean acceptedRequired = channel.paymentStatus() == PaymentSimulatorModels.PaymentStatus.PROCESSING
                || channel.paymentStatus() == PaymentSimulatorModels.PaymentStatus.SUCCEEDED;
        valid &= !acceptedRequired || channel.acceptedAt() != null;
        valid &= channel.acceptedAt() == null || channel.acceptedAt().isBefore(local.paymentExpiresAt());
        valid &= channel.paymentStatus() == PaymentSimulatorModels.PaymentStatus.PROCESSING
                ? channel.resultAt() == null : channel.resultAt() != null;
        valid &= channel.resultAt() == null || channel.acceptedAt() == null || !channel.resultAt().isBefore(channel.acceptedAt());
        valid &= channel.paymentStatus() == PaymentSimulatorModels.PaymentStatus.FAILED
                ? channel.failureCode() != null && !channel.failureCode().isBlank() : channel.failureCode() == null;
        if (!valid) { throw conflict("PAYMENT_FACT_CONFLICT", "支付绑定或受理/结果时间不一致，需要人工核查"); }
    }

    private void clearRecovery(long id, Instant at) {
        mapper.updateRecovery(id, null, null, null, 0, null, null, false, at);
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private Instant earlier(Instant one, Instant two) { return one.isBefore(two) ? one : two; }
    private void requireOne(int changed) { if (changed != 1) { throw new IllegalStateException("Expected one changed row"); } }
    private BusinessException conflict(String code, String message) { return new BusinessException(code, message, HttpStatus.CONFLICT); }
    private BusinessException notFound() { return new BusinessException("NOT_FOUND", "订单或支付不存在", HttpStatus.NOT_FOUND); }
}
