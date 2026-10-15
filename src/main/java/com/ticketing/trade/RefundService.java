package com.ticketing.trade;

import com.ticketing.common.web.BusinessException;
import com.ticketing.simulator.RefundSimulatorModels.*;
import com.ticketing.simulator.RefundSimulatorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import static com.ticketing.trade.RefundModels.*;

/** Durable request -> independent channel -> original notification -> local result -> acknowledgement. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class RefundService {
    private static final Logger log=LoggerFactory.getLogger(RefundService.class);
    private static final Pattern KEY=Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private final RefundTransactionService writer;
    private final RefundQueryService queries;
    private final RefundSimulatorService simulator;
    public RefundService(RefundTransactionService writer,RefundQueryService queries,RefundSimulatorService simulator) {
        this.writer=writer; this.queries=queries; this.simulator=simulator;
    }
    public RefundRecord start(long customerId,long orderId,long ticketId,String key) {
        if(key==null || !KEY.matcher(key).matches()) {
            throw new BusinessException("INVALID_IDEMPOTENCY_KEY","请求编号需为 1–64 位字母、数字、连字符或下划线",HttpStatus.BAD_REQUEST);
        }
        RefundRecord request;
        try { request=writer.begin(customerId,orderId,ticketId,key); }
        catch(DuplicateKeyException concurrentRequest) {
            // The losing transaction is already rolled back before reading the winning request.
            request=writer.begin(customerId,orderId,ticketId,key);
        }
        return recoverIfDue(request.id()); // Replays respect backoff, manual intervention and active claims.
    }
    public RefundRecord state(long id) { return queries.state(id); }
    public RefundRecord owned(long customerId,long orderId,long id) { return queries.owned(customerId,orderId,id); }
    public RefundOrderState order(long customerId,long orderId) { return queries.order(customerId,orderId); }
    public RefundRecord recover(long id) { return recover(id,true); }
    public RefundRecord recoverIfDue(long id) { return recover(id,false); }
    private RefundRecord recover(long id,boolean force) {
        var work=writer.claim(id,force);
        if(work!=null) { process(work); }
        return queries.state(id);
    }
    public RefundRecord applyNotification(Notification notification) {
        try { return writer.applyNotification(notification); }
        catch(BusinessException error) {
            if("REFUND_FACT_CONFLICT".equals(error.getCode())) {
                writer.noteResultFailure(queries.byNumber(notification.refund().refundNumber()).id(),error.getCode());
            }
            throw error;
        }
    }
    public void deliver(String notificationNumber) {
        var ownership=new AtomicReference<Work>();
        var local=new AtomicReference<RefundRecord>();
        try {
            simulator.deliver(notificationNumber,notice -> {
                var original=queries.byNumber(notice.refund().refundNumber());
                local.set(original);
                ownership.set(writer.claim(original.id(),true));
                applyNotification(notice);
            }); // The acknowledgement is after the committed local result.
            if(ownership.get()!=null) { settle(ownership.get()); }
        } catch(RuntimeException failure) {
            String error=category(failure,"REFUND_NOTIFICATION_FAILED");
            if(ownership.get()!=null) { writer.fail(ownership.get().refund().id(),ownership.get().token(),error); }
            else if(local.get()!=null) { writer.noteResultFailure(local.get().id(),error); }
            throw failure;
        }
    }
    private void process(Work work) {
        String phase="REFUND_CHANNEL_UNAVAILABLE";
        try {
            var local=queries.state(work.refund().id());
            var order=queries.originalOrder(local.orderId());
            var found=simulator.find(local.refundNumber());
            Refund channel;
            if(found.isEmpty()) {
                if(local.refundStatus().terminal()) { throw factConflict(); }
                channel=simulator.start(new Binding(local.refundNumber(),local.paymentNumber(),order.orderNumber(),
                        local.ticketNumber(),local.amount()));
            } else { channel=found.orElseThrow(); }
            verifyCurrent(local,channel,order.orderNumber());
            for(var notice:simulator.notifications(local.refundNumber())) {
                if(notice.deliveredAt()!=null) { continue; }
                phase="REFUND_LOCAL_APPLY_FAILED";
                simulator.deliver(notice.notificationNumber(),this::applyNotification);
                phase="REFUND_CHANNEL_UNAVAILABLE";
            }
            settle(work);
        } catch(RuntimeException failure) {
            String error=category(failure,phase);
            writer.fail(work.refund().id(),work.token(),error);
            log.warn("refund_recovery_pending refundId={} category={}",work.refund().id(),error);
        }
    }
    private void settle(Work work) {
        var local=queries.state(work.refund().id());
        var channel=simulator.find(local.refundNumber()).orElseThrow(this::factConflict);
        verifyCurrent(local,channel,queries.originalOrder(local.orderId()).orderNumber());
        var notices=simulator.notifications(local.refundNumber());
        boolean terminal=channel.refundStatus()!=Status.PROCESSING;
        if(terminal && notices.stream().noneMatch(n -> n.refund().refundStatus()==channel.refundStatus())) {
            throw factConflict(); // A terminal query alone never substitutes for the original notification.
        }
        boolean allDelivered=notices.stream().allMatch(n -> n.deliveredAt()!=null);
        // A terminal notice may have appeared after process read the previous list. Leave it for the next pass.
        writer.complete(local.id(),work.token(),terminal && local.refundStatus().terminal() && allDelivered);
    }
    private void verifyCurrent(RefundRecord local,Refund channel,String orderNumber) {
        if(!channel.refundNumber().equals(local.refundNumber()) || !channel.paymentNumber().equals(local.paymentNumber())
                || !channel.merchantOrderNumber().equals(orderNumber) || !channel.ticketNumber().equals(local.ticketNumber())
                || channel.amount().compareTo(local.amount())!=0
                || local.refundStatus().terminal() && !channel.refundStatus().name().equals(local.refundStatus().name())) {
            throw factConflict();
        }
    }
    private String category(RuntimeException failure,String phase) {
        if(failure instanceof BusinessException business && ("REFUND_FACT_CONFLICT".equals(business.getCode())
                || business.getCode().contains("BINDING_CONFLICT"))) { return "REFUND_FACT_CONFLICT"; }
        return phase;
    }
    private BusinessException factConflict() {
        return new BusinessException("REFUND_FACT_CONFLICT","退款事实或原通知缺失、绑定矛盾，需要人工核查",HttpStatus.CONFLICT);
    }
}
