package com.ticketing.ticketing;

import com.ticketing.common.persistence.GeneratedKey;
import org.apache.ibatis.annotations.*;

import java.util.List;

import static com.ticketing.ticketing.TicketModels.Ticket;

@Mapper
interface TicketMapper {
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
