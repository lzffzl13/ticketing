package com.ticketing.inventory;

import com.ticketing.common.persistence.GeneratedKey;
import com.ticketing.inventory.InventoryHoldModels.Hold;
import com.ticketing.inventory.InventoryHoldModels.HoldStatus;
import com.ticketing.inventory.InventoryHoldModels.LockedSeat;
import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;

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

    @Select("""
            SELECT id, order_id, session_id, hold_status AS status, expires_at, created_at, updated_at
            FROM seat_hold WHERE order_id=#{orderId} FOR UPDATE
            """)
    Hold lockByOrder(long orderId);

    @Select("""
            SELECT session_seat_id FROM seat_hold_item
            WHERE hold_id=#{holdId} AND session_id=#{sessionId} ORDER BY session_seat_id
            """)
    List<Long> itemSeatIds(@Param("holdId") long holdId, @Param("sessionId") long sessionId);

    @Select("""
            SELECT id FROM session_seat
            WHERE session_id=#{sessionId} AND current_hold_id=#{holdId} ORDER BY id
            """)
    List<Long> ownedSeatIds(@Param("sessionId") long sessionId, @Param("holdId") long holdId);

    @Select("""
            SELECT id, session_id, seat_status, current_hold_id FROM session_seat
            WHERE session_id=#{sessionId} AND id=#{seatId} FOR UPDATE
            """)
    LockedSeat lockSeat(@Param("sessionId") long sessionId, @Param("seatId") long seatId);

    @Update("""
            UPDATE session_seat SET seat_status='SOLD', current_hold_id=NULL, updated_at=#{at}
            WHERE session_id=#{sessionId} AND id=#{seatId}
              AND seat_status='HELD' AND current_hold_id=#{holdId}
            """)
    int consumeSeat(@Param("sessionId") long sessionId, @Param("seatId") long seatId,
                    @Param("holdId") long holdId, @Param("at") Instant at);

    @Update("""
            UPDATE session_seat SET seat_status='AVAILABLE', current_hold_id=NULL, updated_at=#{at}
            WHERE session_id=#{sessionId} AND id=#{seatId}
              AND seat_status='HELD' AND current_hold_id=#{holdId}
            """)
    int releaseSeat(@Param("sessionId") long sessionId, @Param("seatId") long seatId,
                    @Param("holdId") long holdId, @Param("at") Instant at);

    @Update("""
            UPDATE seat_hold SET hold_status=#{status}, updated_at=#{at}
            WHERE id=#{holdId} AND session_id=#{sessionId} AND hold_status='ACTIVE'
            """)
    int finishHold(@Param("holdId") long holdId, @Param("sessionId") long sessionId,
                   @Param("status") HoldStatus status, @Param("at") Instant at);
}
