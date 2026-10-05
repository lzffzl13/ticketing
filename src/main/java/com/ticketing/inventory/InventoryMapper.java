package com.ticketing.inventory;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.time.Instant;
import java.math.BigDecimal;
import com.ticketing.inventory.InventoryModels.SessionSeat;
import com.ticketing.inventory.InventoryModels.SeatConfiguration;

@Mapper
interface InventoryMapper {
    @Insert("""
            <script>
            INSERT INTO session_seat (session_id, source_seat_template_id, source_area_id,
                area_code_snapshot, area_name_snapshot, area_display_order_snapshot, row_label_snapshot,
                row_order_snapshot, seat_number_snapshot, coordinate_x_snapshot, coordinate_y_snapshot,
                aisle_segment_snapshot, price_tier_id, price_tier_name_snapshot, price_snapshot, seat_status,
                created_at, updated_at) VALUES
            <foreach collection="seats" item="s" separator=",">
                (#{s.sessionId}, #{s.sourceSeatTemplateId}, #{s.sourceAreaId}, #{s.areaCodeSnapshot},
                 #{s.areaNameSnapshot}, #{s.areaDisplayOrderSnapshot}, #{s.rowLabelSnapshot}, #{s.rowOrderSnapshot},
                 #{s.seatNumberSnapshot}, #{s.coordinateXSnapshot}, #{s.coordinateYSnapshot}, #{s.aisleSegmentSnapshot},
                 #{s.priceTierId,jdbcType=BIGINT}, #{s.priceTierNameSnapshot,jdbcType=VARCHAR},
                 #{s.priceSnapshot,jdbcType=DECIMAL}, #{s.seatStatus}, #{s.createdAt}, #{s.updatedAt})
            </foreach>
            </script>
            """)
    int insertSeats(@Param("seats") List<SessionSeat> seats);

    @Select("SELECT COUNT(*) FROM session_seat WHERE session_id=#{sessionId}")
    int countSeats(long sessionId);

    @Select("""
            SELECT * FROM session_seat WHERE session_id=#{sessionId}
            ORDER BY area_display_order_snapshot, source_area_id, row_order_snapshot, seat_number_snapshot
            """)
    List<SessionSeat> seats(long sessionId);

    @Update("""
            UPDATE session_seat SET price_tier_name_snapshot=#{name}, price_snapshot=#{price}, updated_at=#{at}
            WHERE session_id=#{sessionId} AND price_tier_id=#{tierId}
            """)
    int refreshTierPrice(@Param("sessionId") long sessionId, @Param("tierId") long tierId,
                         @Param("name") String name, @Param("price") BigDecimal price, @Param("at") Instant at);

    @Update("""
            UPDATE session_seat SET price_tier_id=#{data.priceTierId,jdbcType=BIGINT},
                price_tier_name_snapshot=#{data.priceTierName,jdbcType=VARCHAR},
                price_snapshot=#{data.price,jdbcType=DECIMAL}, seat_status=#{status}, updated_at=#{at}
            WHERE session_id=#{sessionId} AND id=#{data.id} AND seat_status IN ('AVAILABLE', 'UNAVAILABLE')
            """)
    int configureSeat(@Param("sessionId") long sessionId, @Param("data") SeatConfiguration data,
                      @Param("status") InventoryModels.SeatStatus status, @Param("at") Instant at);
}
