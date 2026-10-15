package com.ticketing.simulator;

import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static com.ticketing.simulator.RefundSimulatorModels.*;
import static com.ticketing.simulator.RefundSimulatorStorage.*;

@Mapper
interface RefundSimulatorMapper {
    String REFUND_COLUMNS = "refund_number,payment_number,merchant_order_number,ticket_number,amount,"
            + "refund_status,accepted_at,result_at,failure_code";

    @Select("""
            SELECT payment_number,merchant_order_number,amount,payment_status FROM simulated_payment
            WHERE payment_number=#{paymentNumber} FOR UPDATE
            """)
    ChannelPayment lockPayment(String paymentNumber);

    @Select("SELECT " + REFUND_COLUMNS + " FROM simulator_refund WHERE refund_number=#{refundNumber}")
    Refund findRefund(String refundNumber);

    @Select("SELECT " + REFUND_COLUMNS + " FROM simulator_refund WHERE refund_number=#{refundNumber} FOR UPDATE")
    Refund lockRefund(String refundNumber);

    @Select("""
            SELECT COALESCE(SUM(amount),0) FROM simulator_refund
            WHERE payment_number=#{paymentNumber} AND refund_status IN ('PROCESSING','SUCCEEDED')
            """)
    BigDecimal reservedAmount(String paymentNumber);

    @Insert("""
            INSERT INTO simulator_refund (refund_number,payment_number,merchant_order_number,ticket_number,
                amount,refund_status,accepted_at,result_at,failure_code,created_at,updated_at)
            VALUES (#{binding.refundNumber},#{binding.paymentNumber},#{binding.merchantOrderNumber},
                #{binding.ticketNumber},#{binding.amount},'PROCESSING',#{at},NULL,NULL,#{at},#{at})
            """)
    int insertRefund(@Param("binding") Binding binding, @Param("at") Instant at);

    @Update("""
            UPDATE simulator_refund SET refund_status=#{status},result_at=#{at},failure_code=#{failureCode},
                updated_at=#{at} WHERE refund_number=#{refundNumber} AND refund_status='PROCESSING'
            """)
    int finishRefund(@Param("refundNumber") String refundNumber, @Param("status") Status status,
                     @Param("at") Instant at, @Param("failureCode") String failureCode);

    @Insert("""
            INSERT INTO simulator_refund_notification (notification_number,refund_number,payment_number,
                merchant_order_number,ticket_number,amount,refund_status,accepted_at,result_at,failure_code,
                notified_at,delivered_at)
            VALUES (#{notificationNumber},#{refund.refundNumber},#{refund.paymentNumber},
                #{refund.merchantOrderNumber},#{refund.ticketNumber},#{refund.amount},#{refund.refundStatus},
                #{refund.acceptedAt},#{refund.resultAt},#{refund.failureCode},#{notifiedAt},NULL)
            """)
    int insertNotification(Notification notification);

    @Select("""
            SELECT * FROM simulator_refund_notification WHERE refund_number=#{refundNumber}
            ORDER BY notified_at,CASE WHEN refund_status='PROCESSING' THEN 0 ELSE 1 END,notification_number
            """)
    List<StoredNotification> notifications(String refundNumber);

    @Select("SELECT * FROM simulator_refund_notification WHERE notification_number=#{notificationNumber}")
    StoredNotification notification(String notificationNumber);

    @Update("""
            UPDATE simulator_refund_notification SET delivered_at=GREATEST(#{at},notified_at)
            WHERE notification_number=#{notificationNumber} AND delivered_at IS NULL
            """)
    int acknowledgeNotification(@Param("notificationNumber") String notificationNumber, @Param("at") Instant at);
}
