package com.ticketing.inventory;

import org.apache.ibatis.annotations.*;
import java.util.List;
import com.ticketing.inventory.InventoryModels.SessionSeat;

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
}
