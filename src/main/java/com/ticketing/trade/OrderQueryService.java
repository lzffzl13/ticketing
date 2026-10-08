package com.ticketing.trade;

import com.ticketing.common.web.BusinessException;
import com.ticketing.inventory.InventoryHoldService;
import com.ticketing.trade.TradeModels.OrderDetails;
import com.ticketing.trade.TradeModels.StoredOrder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderQueryService {
    private final TradeMapper mapper;
    private final InventoryHoldService inventory;

    public OrderQueryService(TradeMapper mapper, InventoryHoldService inventory) {
        this.mapper = mapper;
        this.inventory = inventory;
    }

    // NOT_SUPPORTED can still retain synchronization-bound MyBatis sessions. A fresh read transaction
    // prevents a pre-write cached null from hiding the winner after unique-key arbitration rolls back.
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public StoredOrder findByRequest(long customerId, String key) {
        return mapper.byRequest(customerId, key);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OrderDetails get(long customerId, long orderId) {
        var order = mapper.ownedOrder(customerId, orderId);
        if (order == null) { throw new BusinessException("NOT_FOUND", "订单不存在", HttpStatus.NOT_FOUND); }
        var hold = inventory.getByOrder(orderId);
        var items = mapper.items(orderId);
        if (hold == null || items.isEmpty()) { throw new IllegalStateException("Order hold or items missing"); }
        return new OrderDetails(order.id(), order.orderNumber(), order.sessionId(), order.customerId(),
                order.selectionMode(), order.orderStatus(), order.totalAmount(), order.paymentExpiresAt(), hold, items);
    }
}
