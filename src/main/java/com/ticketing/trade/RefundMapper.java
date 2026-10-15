package com.ticketing.trade;

import com.ticketing.common.persistence.GeneratedKey;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;
import static com.ticketing.trade.RefundModels.*;

@Mapper
interface RefundMapper {
    @Select("SELECT * FROM refund_record WHERE id=#{id}")
    RefundRecord byId(long id);
    @Select("SELECT * FROM refund_record WHERE id=#{id} FOR UPDATE")
    RefundRecord lock(long id);
    @Select("SELECT * FROM refund_record WHERE refund_number=#{number}")
    RefundRecord byNumber(String number);
    @Select("SELECT * FROM refund_record WHERE customer_id=#{customerId} AND request_key=#{key}")
    RefundRecord byRequest(@Param("customerId") long customerId, @Param("key") String key);
    @Select("SELECT * FROM refund_record WHERE customer_id=#{customerId} AND request_key=#{key} FOR UPDATE")
    RefundRecord lockByRequest(@Param("customerId") long customerId, @Param("key") String key);
    @Select("SELECT * FROM refund_record WHERE order_id=#{orderId} ORDER BY id")
    List<RefundRecord> byOrder(long orderId);
    @Insert("""
            INSERT INTO refund_record(refund_number,customer_id,order_id,order_item_id,session_id,
                session_seat_id,ticket_id,ticket_number,payment_id,payment_number,amount,request_key,
                refund_status,requested_at,created_at,updated_at,next_recovery_at)
            VALUES(#{r.refundNumber},#{r.customerId},#{r.orderId},#{r.orderItemId},#{r.sessionId},
                #{r.sessionSeatId},#{r.ticketId},#{r.ticketNumber},#{r.paymentId},#{r.paymentNumber},
                #{r.amount},#{r.requestKey},'REQUESTED',#{r.requestedAt},#{r.createdAt},#{r.updatedAt},#{r.nextRecoveryAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id")
    int insert(@Param("r") RefundRecord refund, @Param("key") GeneratedKey key);
    @Update("""
            UPDATE refund_record SET refund_status=#{status},accepted_at=#{acceptedAt},result_at=#{resultAt},
                failure_code=#{failure},updated_at=#{at},next_recovery_at=#{at}
            WHERE id=#{id} AND refund_status IN ('REQUESTED','PROCESSING')
            """)
    int apply(@Param("id") long id, @Param("status") RefundStatus status,
              @Param("acceptedAt") Instant acceptedAt, @Param("resultAt") Instant resultAt,
              @Param("failure") String failure, @Param("at") Instant at);
    @Update("""
            UPDATE refund_record SET recovery_token=#{token},recovery_claim_until=#{until},
                first_unresolved_at=#{first},consecutive_failures=#{failures},next_recovery_at=#{next},
                recent_error=#{error},manual_required=#{manual},updated_at=#{at} WHERE id=#{id}
            """)
    int recovery(@Param("id") long id, @Param("token") String token, @Param("until") Instant until,
                 @Param("first") Instant first, @Param("failures") int failures, @Param("next") Instant next,
                 @Param("error") String error, @Param("manual") boolean manual, @Param("at") Instant at);
    @Update("""
            UPDATE customer_order SET order_status=#{status},updated_at=#{at}
            WHERE id=#{id} AND order_status IN ('PAID','PARTIALLY_REFUNDED','FULLY_REFUNDED')
            """)
    int summarize(@Param("id") long orderId, @Param("status") TradeModels.OrderStatus status, @Param("at") Instant at);
    @Select("SELECT COALESCE(MAX(id),0) FROM refund_record")
    long upperBound();
    @Select("""
            SELECT id FROM refund_record WHERE id>#{after} AND id<=#{upper}
                AND manual_required=FALSE AND next_recovery_at<=#{at}
                AND (recovery_token IS NULL OR recovery_claim_until<=#{at})
            ORDER BY id LIMIT #{limit}
            """)
    List<Long> due(@Param("at") Instant at, @Param("after") long after, @Param("upper") long upper,
                   @Param("limit") int limit);
}
