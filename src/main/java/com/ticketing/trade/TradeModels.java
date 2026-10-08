package com.ticketing.trade;

import com.ticketing.inventory.InventoryHoldModels.Hold;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class TradeModels {
    private TradeModels() { }

    public enum SelectionMode { MANUAL, RECOMMENDED }
    public enum OrderStatus { PENDING_PAYMENT, PAID, CANCELLED, PARTIALLY_REFUNDED, FULLY_REFUNDED, COMPLETED }

    public record StoredOrder(Long id, String orderNumber, Long customerId, Long sessionId,
                              SelectionMode selectionMode, OrderStatus orderStatus, BigDecimal totalAmount,
                              Instant paymentExpiresAt, String idempotencyKey, String requestDigest,
                              Instant createdAt, Instant updatedAt) { }

    public record OrderItem(Long id, Long sessionSeatId, String areaCodeSnapshot, String areaNameSnapshot,
                            String rowLabelSnapshot, int seatNumberSnapshot, Long priceTierId,
                            String priceTierNameSnapshot, BigDecimal transactionPrice) { }

    public record OrderDetails(Long id, String orderNumber, Long sessionId, Long customerId,
                               SelectionMode selectionMode, OrderStatus orderStatus, BigDecimal totalAmount,
                               Instant paymentExpiresAt, Hold hold, List<OrderItem> items) {
        public OrderDetails { items = List.copyOf(items); }
    }

    public record CreationResult(OrderDetails order, boolean created) { }
}
