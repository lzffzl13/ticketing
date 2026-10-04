package com.ticketing.venue;

import com.ticketing.common.persistence.GeneratedKey;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

import static com.ticketing.venue.VenueModels.*;

@Mapper
interface VenueMapper {
    @Select("SELECT * FROM venue WHERE id = #{id}")
    Venue venueById(long id);

    @Select("SELECT * FROM venue WHERE code = #{code}")
    Venue venueByCode(String code);

    @Insert("""
            INSERT INTO venue (code, name, address, enabled, created_at, updated_at)
            VALUES (#{data.code}, #{data.name}, #{data.address}, #{data.enabled}, #{at}, #{at})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id")
    int insertVenue(@Param("data") VenueRequests.CreateVenue data, @Param("at") Instant at,
                    @Param("key") GeneratedKey key);

    @Update("""
            UPDATE venue SET name=#{data.name}, address=#{data.address}, enabled=#{data.enabled}, updated_at=#{at}
            WHERE id=#{id}
            """)
    int updateVenue(@Param("id") long id, @Param("data") VenueRequests.UpdateVenue data, @Param("at") Instant at);

    @Select("SELECT * FROM area WHERE id = #{id}")
    Area areaById(long id);

    @Select("SELECT * FROM area WHERE id = #{id} FOR UPDATE")
    Area lockArea(long id);

    @Select("SELECT * FROM area WHERE venue_id = #{venueId} ORDER BY display_order, id")
    List<Area> areas(long venueId);

    @Insert("""
            INSERT INTO area (venue_id, code, name, display_order, created_at, updated_at)
            VALUES (#{venueId}, #{data.code}, #{data.name}, #{data.displayOrder}, #{at}, #{at})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id")
    int insertArea(@Param("venueId") long venueId, @Param("data") VenueRequests.CreateArea data,
                   @Param("at") Instant at, @Param("key") GeneratedKey key);

    @Update("UPDATE area SET name=#{data.name}, display_order=#{data.displayOrder}, updated_at=#{at} WHERE id=#{id}")
    int updateArea(@Param("id") long id, @Param("data") VenueRequests.UpdateArea data, @Param("at") Instant at);

    @Select("SELECT * FROM seat_template WHERE id = #{id}")
    SeatTemplate seatById(long id);

    @Select("SELECT * FROM seat_template WHERE id = #{id} FOR UPDATE")
    SeatTemplate lockSeat(long id);

    @Select("""
            SELECT id FROM seat_template WHERE area_id=#{areaId} AND id <> #{excludedId}
              AND ((row_label=#{label} AND row_order <> #{sort}) OR (row_label <> #{label} AND row_order=#{sort}))
            FOR UPDATE
            """)
    List<Long> conflictingRows(@Param("areaId") long areaId, @Param("label") String label,
                               @Param("sort") int sort, @Param("excludedId") long excludedId);

    @Insert("""
            INSERT INTO seat_template (area_id, row_label, row_order, seat_number, coordinate_x,
                coordinate_y, aisle_segment, enabled, created_at, updated_at)
            VALUES (#{areaId}, #{data.rowLabel}, #{data.rowOrder}, #{data.seatNumber}, #{data.coordinateX},
                #{data.coordinateY}, #{data.aisleSegment}, #{data.enabled}, #{at}, #{at})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id")
    int insertSeat(@Param("areaId") long areaId, @Param("data") VenueRequests.SeatInput data,
                   @Param("at") Instant at, @Param("key") GeneratedKey key);

    @Update("""
            UPDATE seat_template SET area_id=#{data.areaId}, row_label=#{data.rowLabel}, row_order=#{data.rowOrder},
                seat_number=#{data.seatNumber}, coordinate_x=#{data.coordinateX}, coordinate_y=#{data.coordinateY},
                aisle_segment=#{data.aisleSegment}, enabled=#{data.enabled}, updated_at=#{at} WHERE id=#{id}
            """)
    int updateSeat(@Param("id") long id, @Param("data") VenueRequests.UpdateSeat data, @Param("at") Instant at);

    @Delete("DELETE FROM seat_template WHERE id = #{id}")
    int deleteSeat(long id);

    @Select("""
            SELECT s.id, s.area_id, a.code AS area_code, a.name AS area_name, a.display_order AS area_display_order,
                   s.row_label, s.row_order, s.seat_number, s.coordinate_x, s.coordinate_y, s.aisle_segment, s.enabled
            FROM seat_template s JOIN area a ON a.id=s.area_id
            WHERE a.venue_id=#{venueId} ORDER BY a.display_order, a.id, s.row_order, s.seat_number
            """)
    List<LayoutSeat> layoutSeats(long venueId);
}
