package com.ticketing.trade;

import org.apache.ibatis.annotations.*;
import java.time.Instant;

@Mapper
interface CheckinOrderMapper {
    @Select("""
            SELECT id,session_id,order_status FROM customer_order WHERE id=#{id} FOR UPDATE
            """)
    CheckinOrderService.OrderState lock(long id);

    @Select("""
            SELECT (SELECT COUNT(*) FROM payment_record p JOIN customer_order o ON o.id=p.order_id
                      WHERE p.order_id=#{orderId} AND p.payment_status='SUCCEEDED'
                        AND p.session_id=o.session_id AND p.amount=o.total_amount)=1
               AND (SELECT COUNT(*) FROM seat_hold WHERE order_id=#{orderId} AND hold_status='CONSUMED')=1
            """)
    boolean paidFacts(long orderId);

    @Update("""
            UPDATE customer_order o SET order_status='COMPLETED',updated_at=#{at}
            WHERE o.id=#{orderId} AND o.order_status IN ('PAID','PARTIALLY_REFUNDED')
              AND EXISTS (SELECT 1 FROM ticket t WHERE t.order_id=o.id AND t.ticket_status='CHECKED_IN')
              AND NOT EXISTS (SELECT 1 FROM ticket t WHERE t.order_id=o.id
                               AND t.ticket_status NOT IN ('CHECKED_IN','REFUNDED'))
              AND (SELECT COUNT(*) FROM ticket t WHERE t.order_id=o.id)
                    = (SELECT COUNT(*) FROM order_item i WHERE i.order_id=o.id)
              AND NOT EXISTS (SELECT 1 FROM refund_record r WHERE r.order_id=o.id
                               AND r.refund_status IN ('REQUESTED','PROCESSING'))
            """)
    int completeIfFulfilled(@Param("orderId") long orderId, @Param("at") Instant at);
}
