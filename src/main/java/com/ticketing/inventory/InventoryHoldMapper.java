package com.ticketing.inventory;

import com.ticketing.common.persistence.GeneratedKey;
import com.ticketing.inventory.InventoryHoldModels.Hold;
import org.apache.ibatis.annotations.*;

import java.time.Instant;

@Mapper
interface InventoryHoldMapper {
    @Insert("""
            INSERT INTO seat_hold (order_id, session_id, hold_status, expires_at, created_at, updated_at)
            VALUES (#{orderId}, #{sessionId}, 'ACTIVE', #{expiresAt}, #{at}, #{at})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id")
    int insertHold(@Param("orderId") long orderId, @Param("sessionId") long sessionId,
                   @Param("expiresAt") Instant expiresAt, @Param("at") Instant at,
                   @Param("key") GeneratedKey key);

    @Insert("""
            INSERT INTO seat_hold_item (hold_id, session_id, session_seat_id, created_at)
            VALUES (#{holdId}, #{sessionId}, #{seatId}, #{at})
            """)
    int insertItem(@Param("holdId") long holdId, @Param("sessionId") long sessionId,
                   @Param("seatId") long seatId, @Param("at") Instant at);

    @Update("""
            UPDATE session_seat SET seat_status='HELD', current_hold_id=#{holdId}, updated_at=#{at}
            WHERE session_id=#{sessionId} AND id=#{seatId}
              AND seat_status='AVAILABLE' AND current_hold_id IS NULL
            """)
    int holdSeat(@Param("sessionId") long sessionId, @Param("seatId") long seatId,
                 @Param("holdId") long holdId, @Param("at") Instant at);

    @Select("""
            SELECT id, order_id, session_id, hold_status AS status, expires_at, created_at, updated_at
            FROM seat_hold WHERE order_id=#{orderId}
            """)
    Hold byOrder(long orderId);
}
