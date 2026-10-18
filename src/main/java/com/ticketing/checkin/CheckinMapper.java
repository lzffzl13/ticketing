package com.ticketing.checkin;

import com.ticketing.common.persistence.GeneratedKey;
import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;

import static com.ticketing.checkin.CheckinModels.Attempt;

@Mapper
interface CheckinMapper {
    String COLUMNS = "id,request_id,checker_id,submitted_session_id,session_id,ticket_id,credential_digest,attempted_at,result";

    @Insert("""
            INSERT INTO checkin_record(request_id,checker_id,submitted_session_id,session_id,ticket_id,
                credential_digest,attempted_at,result)
            VALUES(#{data.requestId},#{data.checkerId},#{data.submittedSessionId},#{data.sessionId},
                #{data.ticketId},#{data.credentialDigest},#{data.attemptedAt},#{data.result})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id")
    int insert(@Param("data") Attempt data, @Param("key") GeneratedKey key);

    @Select("""
            <script>
            SELECT id,request_id,checker_id,submitted_session_id,session_id,ticket_id,credential_digest,attempted_at,result
            FROM checkin_record WHERE id &gt; #{afterId}
            <if test="sessionId != null">AND submitted_session_id=#{sessionId}</if>
            <if test="ticketId != null">AND ticket_id=#{ticketId}</if>
            <if test="from != null">AND attempted_at &gt;= #{from}</if>
            <if test="to != null">AND attempted_at &lt; #{to}</if>
            ORDER BY id LIMIT #{limit}
            </script>
            """)
    List<Attempt> query(@Param("sessionId") Long sessionId, @Param("ticketId") Long ticketId,
                        @Param("from") Instant from, @Param("to") Instant to,
                        @Param("afterId") long afterId, @Param("limit") int limit);
}
