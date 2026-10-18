package com.ticketing.ticketing;

import com.ticketing.common.persistence.GeneratedKey;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.time.Instant;

import static com.ticketing.ticketing.TicketModels.Ticket;

@Mapper
interface TicketMapper {
    @Select("SELECT id,ticket_number,order_item_id,order_id,session_id,session_seat_id,ticket_status,issued_at FROM ticket WHERE ticket_number=#{number}")
    Ticket byNumber(String number);

    @Select("SELECT id,ticket_number,order_item_id,order_id,session_id,session_seat_id,ticket_status,issued_at FROM ticket WHERE id=#{id} FOR UPDATE")
    Ticket lock(long id);

    @Update("UPDATE ticket SET ticket_status=#{target},updated_at=#{at} WHERE id=#{id} AND ticket_status=#{expected}")
    int transition(@Param("id") long id, @Param("expected") TicketModels.TicketStatus expected,
                   @Param("target") TicketModels.TicketStatus target, @Param("at") Instant at);
    @Insert("""
            INSERT INTO ticket (ticket_number, order_item_id, order_id, session_id, session_seat_id,
                ticket_status, issued_at, updated_at)
            VALUES (#{data.ticketNumber}, #{data.orderItemId}, #{data.orderId}, #{data.sessionId},
                #{data.sessionSeatId}, #{data.ticketStatus}, #{data.issuedAt}, #{data.issuedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id")
    int insertTicket(@Param("data") Ticket data, @Param("key") GeneratedKey key);

    @Select("""
            SELECT id, ticket_number, order_item_id, order_id, session_id, session_seat_id,
                   ticket_status, issued_at FROM ticket WHERE order_id=#{orderId}
            ORDER BY session_seat_id, id
            """)
    List<Ticket> byOrder(long orderId);
}
