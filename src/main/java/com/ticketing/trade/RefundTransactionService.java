package com.ticketing.trade;

import com.ticketing.catalog.CatalogModels.Session;
import com.ticketing.catalog.CatalogPurchaseService;
import com.ticketing.common.persistence.GeneratedKey;
import com.ticketing.common.web.BusinessException;
import com.ticketing.inventory.InventoryHoldService;
import com.ticketing.simulator.RefundSimulatorModels.Notification;
import com.ticketing.ticketing.TicketModels.Ticket;
import com.ticketing.ticketing.TicketModels.TicketStatus;
import com.ticketing.ticketing.TicketService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import static com.ticketing.trade.RefundModels.*;
import static com.ticketing.trade.TradeModels.OrderStatus.*;

/** Only short local transactions. No simulator call is made while these locks are held. */
@Service
@Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
public class RefundTransactionService {
    private final RefundMapper mapper;
    private final PaymentMapper payments;
    private final TradeMapper orders;
    private final CatalogPurchaseService catalog;
    private final InventoryHoldService inventory;
    private final TicketService tickets;
    private final Clock clock;
    public RefundTransactionService(RefundMapper mapper,PaymentMapper payments,TradeMapper orders,
            CatalogPurchaseService catalog,InventoryHoldService inventory,TicketService tickets,Clock clock) {
        this.mapper=mapper; this.payments=payments; this.orders=orders; this.catalog=catalog;
        this.inventory=inventory; this.tickets=tickets; this.clock=clock;
    }

    public RefundRecord begin(long customerId,long orderId,long ticketId,String key) {
        var original=mapper.byRequest(customerId,key);
        if (original!=null) { return replay(original,orderId,ticketId); }
        var locked=lockOrder(orderId,customerId);
        var order=locked.order();
        var history=payments.lockPayments(orderId);
        // Use a distinct locking statement: MyBatis may cache the earlier pre-lock miss in this SqlSession.
        original=mapper.lockByRequest(customerId,key);
        if(original!=null) { return replay(original,orderId,ticketId); }
        var located=tickets.listByOrder(orderId).stream().filter(t -> t.id()==ticketId).findFirst().orElseThrow(this::missing);
        if(order.orderStatus()!=PAID && order.orderStatus()!=PARTIALLY_REFUNDED) { throw notRefundable(); }
        inventory.lockSoldSeat(order.sessionId(),located.sessionSeatId());
        var ticket=tickets.lock(ticketId);
        if(ticket==null || ticket.orderId()!=orderId || ticket.ticketStatus()!=TicketStatus.VALID) { throw notRefundable(); }
        var at=now(); // Do not use the time before waiting for the business locks.
        if(!at.isBefore(locked.session().admissionOpenAt())) {
            throw conflict("REFUND_APPLICATION_CLOSED","已到入场开放时间，不能申请新退票");
        }
        var paid=history.stream().filter(p -> p.paymentStatus()==PaymentModels.PaymentStatus.SUCCEEDED)
                .findFirst().orElseThrow(this::notRefundable);
        var item=orders.items(orderId).stream().filter(i -> i.id().equals(ticket.orderItemId())).findFirst().orElseThrow();
        var refund=new RefundRecord(null,UUID.randomUUID().toString(),customerId,orderId,item.id(),order.sessionId(),
                ticket.sessionSeatId(),ticket.id(),ticket.ticketNumber(),paid.id(),paid.paymentNumber(),
                item.transactionPrice(),key,RefundStatus.REQUESTED,at,null,null,null,at,at,null,0,at,null,false,null,null);
        var generated=new GeneratedKey();
        one(mapper.insert(refund,generated));
        tickets.transition(ticketId,TicketStatus.VALID,TicketStatus.REFUNDING,at);
        return mapper.byId(generated.getId());
    }

    public RefundRecord applyNotification(Notification notification) {
        var channel=notification.refund();
        var located=mapper.byNumber(channel.refundNumber());
        if(located==null) { throw missing(); }
        var locked=lockOrder(located.orderId(),null);
        var history=payments.lockPayments(located.orderId());
        var local=mapper.lock(located.id());
        var paid=history.stream().filter(p -> p.id().equals(local.paymentId())).findFirst().orElseThrow();
        boolean valid=paid.paymentStatus()==PaymentModels.PaymentStatus.SUCCEEDED
                && channel.paymentNumber().equals(local.paymentNumber())
                && paid.paymentNumber().equals(local.paymentNumber())
                && channel.merchantOrderNumber().equals(locked.order().orderNumber())
                && channel.ticketNumber().equals(local.ticketNumber())
                && channel.amount().compareTo(local.amount())==0 && channel.acceptedAt()!=null
                && (channel.refundStatus().name().equals("PROCESSING") ? channel.resultAt()==null : channel.resultAt()!=null)
                && (channel.resultAt()==null || !channel.resultAt().isBefore(channel.acceptedAt()))
                && (channel.refundStatus().name().equals("FAILED")
                    ? channel.failureCode()!=null && !channel.failureCode().isBlank() : channel.failureCode()==null);
        if(!valid) { throw factConflict(); }
        var target=RefundStatus.valueOf(channel.refundStatus().name());
        // This precedes any seat access: the seat may already belong to a new purchase.
        if(local.refundStatus().terminal()) {
            if(target==RefundStatus.PROCESSING || target==local.refundStatus()) { return local; }
            throw factConflict();
        }
        if(locked.order().orderStatus()!=PAID && locked.order().orderStatus()!=PARTIALLY_REFUNDED) { throw factConflict(); }
        inventory.lockSoldSeat(local.sessionId(),local.sessionSeatId());
        var ticket=tickets.lock(local.ticketId());
        if(!sameTicket(local,ticket) || ticket.ticketStatus()!=TicketStatus.REFUNDING) { throw factConflict(); }
        var at=now();
        if(channel.resultAt()!=null && at.isBefore(channel.resultAt())) { at=channel.resultAt(); }
        one(mapper.apply(local.id(),target,channel.acceptedAt(),channel.resultAt(),channel.failureCode(),at));
        if(target==RefundStatus.SUCCEEDED) {
            tickets.transition(local.ticketId(),TicketStatus.REFUNDING,TicketStatus.REFUNDED,at);
            inventory.refundSeat(local.sessionId(),local.sessionSeatId(),at);
            var all=tickets.listByOrder(local.orderId());
            boolean fully=all.stream().allMatch(t -> t.ticketStatus()==TicketStatus.REFUNDED);
            one(mapper.summarize(local.orderId(),fully ? FULLY_REFUNDED : PARTIALLY_REFUNDED,at));
        } else if(target==RefundStatus.FAILED) {
            tickets.transition(local.ticketId(),TicketStatus.REFUNDING,TicketStatus.VALID,at);
        }
        // Keep next_recovery_at even for terminal results until their notification acknowledgements finish.
        return mapper.byId(local.id());
    }

    public Work claim(long id,boolean force) {
        var local=lockRefund(id);
        var at=now();
        if(local.nextRecoveryAt()==null && local.refundStatus().terminal()) { return null; }
        if(local.recoveryToken()!=null && at.isBefore(local.recoveryClaimUntil())) { return null; }
        if(!force && (local.manualRequired() || local.nextRecoveryAt()!=null && at.isBefore(local.nextRecoveryAt()))) { return null; }
        int failures=local.consecutiveFailures();
        var first=local.firstUnresolvedAt();
        String error=local.recentError();
        boolean manual=local.manualRequired();
        if(local.recoveryToken()!=null) {
            failures++;
            if(first==null) { first=local.recoveryClaimUntil(); }
            error="RECOVERY_LEASE_EXPIRED";
            manual |= RecoveryPolicy.requiresManual(failures,first,at);
            if(!force && manual) {
                mapper.recovery(id,null,null,first,failures,at.plus(RecoveryPolicy.backoff(failures)),error,true,at);
                return null;
            }
        }
        String token=UUID.randomUUID().toString();
        mapper.recovery(id,token,at.plus(RecoveryPolicy.LEASE),first,failures,at,error,manual,at);
        return new Work(mapper.byId(id),token);
    }

    public void complete(long id,String token,boolean terminalAndAllDelivered) {
        var local=lockRefund(id);
        if(token==null || !Objects.equals(token,local.recoveryToken())) { return; }
        var at=now();
        Instant next=terminalAndAllDelivered && local.refundStatus().terminal() ? null : at.plusSeconds(5);
        mapper.recovery(id,null,null,null,0,next,null,false,at);
    }
    public void fail(long id,String token,String error) {
        var local=lockRefund(id);
        if(token==null || !Objects.equals(token,local.recoveryToken())) { return; }
        var at=now();
        var first=local.firstUnresolvedAt()==null ? at : local.firstUnresolvedAt();
        int failures=local.consecutiveFailures()+1;
        boolean manual="REFUND_FACT_CONFLICT".equals(error) || RecoveryPolicy.requiresManual(failures,first,at);
        mapper.recovery(id,null,null,first,failures,at.plus(RecoveryPolicy.backoff(failures)),error,manual,at);
    }
    public void noteResultFailure(long id,String error) {
        var local=lockRefund(id);
        var at=now();
        var first=local.firstUnresolvedAt()==null ? at : local.firstUnresolvedAt();
        var next=local.nextRecoveryAt()==null || at.plusSeconds(5).isBefore(local.nextRecoveryAt())
                ? at.plusSeconds(5) : local.nextRecoveryAt();
        if("REFUND_FACT_CONFLICT".equals(local.recentError())) { error=local.recentError(); }
        boolean manual=local.manualRequired() || "REFUND_FACT_CONFLICT".equals(error)
                || RecoveryPolicy.requiresManual(local.consecutiveFailures(),first,at);
        mapper.recovery(id,local.recoveryToken(),local.recoveryClaimUntil(),first,local.consecutiveFailures(),next,error,manual,at);
    }
    private RefundRecord lockRefund(long id) {
        var located=mapper.byId(id);
        if(located==null) { throw missing(); }
        lockOrder(located.orderId(),null);
        payments.lockPayments(located.orderId());
        return mapper.lock(id);
    }
    private LockedOrder lockOrder(long id,Long customerId) {
        var located=payments.orderState(id);
        if(located==null || customerId!=null && !customerId.equals(located.customerId())) { throw missing(); }
        var session=catalog.lockForPurchase(located.sessionId()).session();
        var current=payments.lockOrder(id);
        if(current==null || customerId!=null && !customerId.equals(current.customerId())) { throw missing(); }
        return new LockedOrder(current,session);
    }
    private RefundRecord replay(RefundRecord original,long orderId,long ticketId) {
        if(original.orderId()!=orderId || original.ticketId()!=ticketId) {
            throw conflict("IDEMPOTENCY_CONFLICT","请求编号已绑定其他订单或电子票");
        }
        return original;
    }
    private boolean sameTicket(RefundRecord r,Ticket t) {
        return t!=null && t.orderId().equals(r.orderId()) && t.orderItemId().equals(r.orderItemId())
                && t.sessionId().equals(r.sessionId()) && t.sessionSeatId().equals(r.sessionSeatId())
                && t.ticketNumber().equals(r.ticketNumber());
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private void one(int changed) { if(changed!=1) { throw new IllegalStateException("Refund transaction did not change exactly one row"); } }
    private BusinessException missing() { return new BusinessException("NOT_FOUND","订单、电子票或退票单不存在",HttpStatus.NOT_FOUND); }
    private BusinessException notRefundable() { return conflict("TICKET_NOT_REFUNDABLE","当前订单或电子票状态不允许新退票"); }
    private BusinessException factConflict() { return conflict("REFUND_FACT_CONFLICT","退款绑定或终态矛盾，需要人工核查"); }
    private BusinessException conflict(String code,String message) { return new BusinessException(code,message,HttpStatus.CONFLICT); }
    private record LockedOrder(PaymentModels.RecoveryOrder order,Session session) { }
}
