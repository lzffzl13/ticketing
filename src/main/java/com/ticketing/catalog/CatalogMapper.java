package com.ticketing.catalog;

import com.ticketing.common.persistence.GeneratedKey;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

import static com.ticketing.catalog.CatalogModels.*;

@Mapper
interface CatalogMapper {
    @Insert("""
            INSERT INTO event (code, name, description, enabled, created_at, updated_at)
            VALUES (#{data.code}, #{data.name}, #{data.description}, #{data.enabled}, #{at}, #{at})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id")
    int insertEvent(@Param("data") CatalogRequests.CreateEvent data, @Param("at") Instant at, @Param("key") GeneratedKey key);

    @Select("SELECT * FROM event WHERE id=#{id}")
    Event eventById(long id);

    @Select("SELECT * FROM event WHERE code=#{code}")
    Event eventByCode(String code);

    @Insert("""
            INSERT INTO session (code, event_id, venue_id, event_name_snapshot, venue_name_snapshot, starts_at,
                admission_open_at, admission_close_at, sales_status, first_opened_at, forbid_new_orphan, created_at, updated_at)
            VALUES (#{data.code}, #{data.eventId}, #{data.venueId}, #{data.eventNameSnapshot}, #{data.venueNameSnapshot},
                #{data.startsAt}, #{data.admissionOpenAt}, #{data.admissionCloseAt}, 'DRAFT', NULL,
                #{data.forbidNewOrphan}, #{data.createdAt}, #{data.updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id")
    int insertSession(@Param("data") Session data, @Param("key") GeneratedKey key);

    @Select("SELECT * FROM session WHERE id=#{id}")
    Session sessionById(long id);

    @Select("SELECT * FROM session WHERE code=#{code}")
    Session sessionByCode(String code);

    @Insert("""
            INSERT INTO price_tier (session_id, code, name, price, enabled, created_at, updated_at)
            VALUES (#{sessionId}, #{data.code}, #{data.name}, #{data.price}, TRUE, #{at}, #{at})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id")
    int insertTier(@Param("sessionId") long sessionId, @Param("data") CatalogRequests.PriceTierInput data,
                   @Param("at") Instant at, @Param("key") GeneratedKey key);

    @Select("SELECT * FROM price_tier WHERE session_id=#{sessionId} ORDER BY id")
    List<PriceTier> tiers(long sessionId);
}
