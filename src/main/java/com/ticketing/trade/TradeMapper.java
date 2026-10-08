package com.ticketing.trade;

import com.ticketing.common.persistence.GeneratedKey;
import com.ticketing.inventory.InventoryModels.SessionSeat;
import com.ticketing.trade.TradeModels.OrderItem;
import com.ticketing.trade.TradeModels.StoredOrder;
import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;

@Mapper
interface TradeMapper {
    @Select("SELECT * FROM customer_order WHERE customer_id=#{customerId} AND idempotency_key=#{key}")
    StoredOrder byRequest(@Param("customerId") long customerId, @Param("key") String key);

    @Select("SELECT * FROM customer_order WHERE id=#{id} AND customer_id=#{customerId}")
    StoredOrder ownedOrder(@Param("customerId") long customerId, @Param("id") long id);

    @Select("""
            SELECT id, session_seat_id, area_code_snapshot, area_name_snapshot, row_label_snapshot,
                   seat_number_snapshot, price_tier_id, price_tier_name_snapshot, transaction_price
            FROM order_item WHERE order_id=#{orderId} ORDER BY session_seat_id
            """)
    List<OrderItem> items(long orderId);

    @Insert("""
            INSERT INTO customer_order (order_number, customer_id, session_id, selection_mode, order_status,
                total_amount, payment_expires_at, idempotency_key, request_digest, created_at, updated_at)
            VALUES (#{data.orderNumber}, #{data.customerId}, #{data.sessionId}, #{data.selectionMode},
                #{data.orderStatus}, #{data.totalAmount}, #{data.paymentExpiresAt}, #{data.idempotencyKey},
                #{data.requestDigest}, #{data.createdAt}, #{data.updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id")
    int insertOrder(@Param("data") StoredOrder data, @Param("key") GeneratedKey key);

    @Insert("""
            INSERT INTO order_item (order_id, session_id, session_seat_id, area_code_snapshot,
                area_name_snapshot, row_label_snapshot, seat_number_snapshot, price_tier_id,
                price_tier_name_snapshot, transaction_price, created_at)
            VALUES (#{orderId}, #{sessionId}, #{seat.id}, #{seat.areaCodeSnapshot}, #{seat.areaNameSnapshot},
                #{seat.rowLabelSnapshot}, #{seat.seatNumberSnapshot}, #{seat.priceTierId},
                #{seat.priceTierNameSnapshot}, #{seat.priceSnapshot}, #{at})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id")
    int insertItem(@Param("orderId") long orderId, @Param("sessionId") long sessionId,
                   @Param("seat") SessionSeat seat, @Param("at") Instant at, @Param("key") GeneratedKey key);
}
