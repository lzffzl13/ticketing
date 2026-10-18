package com.ticketing.consistency;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import static com.ticketing.consistency.ConsistencySnapshot.*;

/** This reporting mapper reads local facts only; it never uses a locking/current read. */
@Mapper
interface ConsistencyMapper {
    @Select("SELECT id,admission_close_at FROM session WHERE id=#{id}")
    Session session(long id);

    @Select("SELECT COALESCE(MAX(id),0) FROM session")
    long upperBound();

    @Select("SELECT id FROM session WHERE id>#{after} AND id<=#{upper} ORDER BY id LIMIT #{limit}")
    List<Long> sessions(@Param("after") long after, @Param("upper") long upper, @Param("limit") int limit);

    @Select("SELECT id,order_status,total_amount,payment_expires_at,manual_required FROM customer_order WHERE session_id=#{id} ORDER BY id")
    List<Order> orders(long id);

    @Select("SELECT id,order_id,session_seat_id,transaction_price FROM order_item WHERE session_id=#{id} ORDER BY id")
    List<Item> items(long id);

    @Select("SELECT id,order_id,hold_status,expires_at FROM seat_hold WHERE session_id=#{id} ORDER BY id")
    List<Hold> holds(long id);

    @Select("SELECT hold_id,session_seat_id FROM seat_hold_item WHERE session_id=#{id} ORDER BY hold_id,session_seat_id")
    List<HoldItem> holdItems(long id);

    @Select("SELECT id,order_id,payment_status,amount,payment_expires_at FROM payment_record WHERE session_id=#{id} ORDER BY id")
    List<Payment> payments(long id);

    @Select("SELECT id,seat_status,current_hold_id FROM session_seat WHERE session_id=#{id} ORDER BY id")
    List<Seat> seats(long id);

    @Select("SELECT id,order_id,order_item_id,session_seat_id,ticket_status FROM ticket WHERE session_id=#{id} ORDER BY id")
    List<Ticket> tickets(long id);

    @Select("SELECT id,order_id,order_item_id,ticket_id,payment_id,refund_status,amount,manual_required FROM refund_record WHERE session_id=#{id} ORDER BY id")
    List<Refund> refunds(long id);

    @Select("""
            SELECT c.id,c.ticket_id,c.session_id,c.submitted_session_id
            FROM checkin_record c LEFT JOIN ticket t ON t.id=c.ticket_id
            WHERE c.result='SUCCESS' AND (c.session_id=#{id} OR c.submitted_session_id=#{id} OR t.session_id=#{id})
            ORDER BY c.id
            """)
    List<Checkin> checkins(long id);
}
