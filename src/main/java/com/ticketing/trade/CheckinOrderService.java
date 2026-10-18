package com.ticketing.trade;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

/** Explicit trade boundary for check-in; the caller already holds the session lock. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CheckinOrderService {
    private final CheckinOrderMapper mapper;
    public CheckinOrderService(CheckinOrderMapper mapper) { this.mapper = mapper; }

    public record OrderState(long id, long sessionId, TradeModels.OrderStatus orderStatus) { }

    public OrderState lock(long id) { return mapper.lock(id); }

    public boolean permitsCheckin(OrderState order, long sessionId) {
        return order != null && order.sessionId() == sessionId
                && (order.orderStatus() == TradeModels.OrderStatus.PAID
                    || order.orderStatus() == TradeModels.OrderStatus.PARTIALLY_REFUNDED)
                && mapper.paidFacts(order.id());
    }

    public void completeIfFulfilled(long orderId, Instant at) { mapper.completeIfFulfilled(orderId, at); }
}
