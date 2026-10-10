package com.ticketing.trade;

import com.ticketing.common.persistence.GeneratedKey;
import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;
import static com.ticketing.trade.PaymentModels.*;

@Mapper
interface PaymentMapper {
    String ORDER_COLUMNS = "id,order_number,customer_id,session_id,order_status,total_amount,payment_expires_at,"
            + "cancellation_requested_at,cancellation_reason,first_unresolved_at,consecutive_failures,"
            + "next_recovery_at,recent_error,manual_required,recovery_token,recovery_claim_until";
    String PAYMENT_COLUMNS = "id,order_id,session_id,payment_number,amount,payment_expires_at,request_key,"
            + "payment_status,accepted_at,result_at,failure_code,created_at,updated_at";
    @Select("SELECT " + ORDER_COLUMNS + " FROM customer_order WHERE id=#{id}")
    RecoveryOrder orderState(long id);
    @Select("SELECT " + ORDER_COLUMNS + " FROM customer_order WHERE id=#{id} FOR UPDATE")
    RecoveryOrder lockOrder(long id);
    @Select("SELECT " + PAYMENT_COLUMNS + " FROM payment_record WHERE order_id=#{orderId} ORDER BY id")
    List<PaymentRecord> payments(long orderId);
    @Select("SELECT " + PAYMENT_COLUMNS + " FROM payment_record WHERE order_id=#{orderId} ORDER BY id FOR UPDATE")
    List<PaymentRecord> lockPayments(long orderId);
    @Select("SELECT " + PAYMENT_COLUMNS + " FROM payment_record WHERE payment_number=#{number}")
    PaymentRecord byNumber(String number);
    @Select("SELECT " + PAYMENT_COLUMNS + " FROM payment_record WHERE order_id=#{orderId} AND request_key=#{key}")
    PaymentRecord byRequest(@Param("orderId") long orderId, @Param("key") String key);
    @Insert("""
            INSERT INTO payment_record(order_id,session_id,payment_number,amount,payment_expires_at,request_key,
                payment_status,accepted_at,result_at,failure_code,created_at,updated_at)
            VALUES(#{data.orderId},#{data.sessionId},#{data.paymentNumber},#{data.amount},#{data.paymentExpiresAt},
                #{data.requestKey},#{data.paymentStatus},#{data.acceptedAt},#{data.resultAt},#{data.failureCode},
                #{data.createdAt},#{data.updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id")
    int insertPayment(@Param("data") PaymentRecord data, @Param("key") GeneratedKey key);
    @Update("""
            UPDATE payment_record SET payment_status=#{data.paymentStatus},accepted_at=#{data.acceptedAt},
                result_at=#{data.resultAt},failure_code=#{data.failureCode},updated_at=#{data.updatedAt}
            WHERE id=#{data.id} AND payment_status IN ('INITIATED','PROCESSING')
            """)
    int applyPayment(@Param("data") PaymentRecord data);
    @Update("""
            UPDATE customer_order SET cancellation_requested_at=#{at},cancellation_reason=#{reason},updated_at=#{at}
            WHERE id=#{orderId} AND cancellation_requested_at IS NULL AND order_status='PENDING_PAYMENT'
            """)
    int requestCancellation(@Param("orderId") long orderId, @Param("reason") String reason, @Param("at") Instant at);
    @Update("""
            UPDATE customer_order SET order_status=#{status},updated_at=#{at}
            WHERE id=#{orderId} AND order_status='PENDING_PAYMENT'
            """)
    int finishOrder(@Param("orderId") long orderId, @Param("status") TradeModels.OrderStatus status, @Param("at") Instant at);
    @Update("""
            UPDATE customer_order SET recovery_token=#{token},recovery_claim_until=#{claimedUntil},
                first_unresolved_at=#{firstUnresolved},consecutive_failures=#{failures},next_recovery_at=#{nextAt},
                recent_error=#{error},manual_required=#{manual},updated_at=#{at} WHERE id=#{orderId}
            """)
    int updateRecovery(@Param("orderId") long orderId, @Param("token") String token,
                       @Param("claimedUntil") Instant claimedUntil, @Param("firstUnresolved") Instant firstUnresolved,
                       @Param("failures") int failures, @Param("nextAt") Instant nextAt,
                       @Param("error") String error, @Param("manual") boolean manual, @Param("at") Instant at);
}
