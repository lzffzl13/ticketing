package com.ticketing.trade;

import com.ticketing.common.web.BusinessException;
import com.ticketing.ticketing.TicketModels.Ticket;
import com.ticketing.ticketing.TicketService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import static com.ticketing.trade.PaymentModels.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
public class PaymentQueryService {
    private final PaymentMapper mapper;
    private final TicketService tickets;
    public PaymentQueryService(PaymentMapper mapper, TicketService tickets) { this.mapper = mapper; this.tickets = tickets; }
    public List<PaymentRecord> payments(long customerId, long orderId) {
        owned(customerId, orderId); return mapper.payments(orderId);
    }
    public List<Ticket> tickets(long customerId, long orderId) {
        owned(customerId, orderId); return tickets.listByOrder(orderId);
    }
    public OrderPaymentState ownedState(long customerId, long orderId) {
        return view(owned(customerId, orderId));
    }
    public OrderPaymentState state(long orderId) { return view(order(orderId)); }
    public PaymentRecord payment(String number) {
        var payment = mapper.byNumber(number);
        if (payment == null) { throw missing(); }
        return payment;
    }
    public RecoveryOrder order(long orderId) {
        var order = mapper.orderState(orderId);
        if (order == null) { throw missing(); }
        return order;
    }
    private RecoveryOrder owned(long customerId, long orderId) {
        var order = order(orderId);
        if (order.customerId() != customerId) { throw missing(); }
        return order;
    }
    private OrderPaymentState view(RecoveryOrder order) {
        return new OrderPaymentState(order.id(), order.orderStatus(), order.cancellationRequestedAt(),
                order.cancellationReason(), order.firstUnresolvedAt(), order.consecutiveFailures(),
                order.nextRecoveryAt(), order.recentError(), order.manualRequired(), order.recoveryClaimUntil(),
                mapper.payments(order.id()));
    }
    private BusinessException missing() { return new BusinessException("NOT_FOUND", "订单或支付不存在", HttpStatus.NOT_FOUND); }
}
