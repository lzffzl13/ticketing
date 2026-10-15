package com.ticketing.trade;

import com.ticketing.common.web.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static com.ticketing.trade.RefundModels.*;

@Service
@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
public class RefundQueryService {
    private final RefundMapper mapper;
    private final PaymentMapper orders;
    public RefundQueryService(RefundMapper mapper, PaymentMapper orders) { this.mapper=mapper; this.orders=orders; }
    public RefundRecord state(long id) { return require(mapper.byId(id)); }
    public RefundRecord byNumber(String number) { return require(mapper.byNumber(number)); }
    public RefundRecord owned(long customerId, long orderId, long refundId) {
        ownedOrder(customerId, orderId);
        var result=state(refundId);
        if (result.orderId()!=orderId) { throw missing(); }
        return result;
    }
    public RefundOrderState order(long customerId, long orderId) {
        var order=ownedOrder(customerId, orderId);
        var refunds=mapper.byOrder(orderId);
        var total=refunds.stream().filter(r -> r.refundStatus()==RefundStatus.SUCCEEDED)
                .map(RefundRecord::amount).reduce(new BigDecimal("0.00"), BigDecimal::add);
        return new RefundOrderState(orderId, order.orderStatus(), total, refunds);
    }
    public long upperBound() { return mapper.upperBound(); }
    public List<Long> due(Instant at,long after,long upper,int limit) { return mapper.due(at,after,upper,limit); }
    PaymentModels.RecoveryOrder originalOrder(long id) {
        var result=orders.orderState(id);
        if (result==null) { throw missing(); }
        return result;
    }
    private PaymentModels.RecoveryOrder ownedOrder(long customerId,long id) {
        var order=originalOrder(id);
        if (order.customerId()!=customerId) { throw missing(); }
        return order;
    }
    private RefundRecord require(RefundRecord record) { if(record==null) { throw missing(); } return record; }
    private BusinessException missing() { return new BusinessException("NOT_FOUND","订单或退票单不存在", HttpStatus.NOT_FOUND); }
}
