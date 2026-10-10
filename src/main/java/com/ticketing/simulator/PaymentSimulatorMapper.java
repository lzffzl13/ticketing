package com.ticketing.simulator;

import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;

import static com.ticketing.simulator.PaymentSimulatorModels.*;
import static com.ticketing.simulator.SimulatorStorage.*;

@Mapper
interface PaymentSimulatorMapper {
    @Select("SELECT * FROM simulated_payment WHERE payment_number=#{paymentNumber}")
    StoredPayment findPayment(String paymentNumber);

    @Select("SELECT * FROM simulated_payment WHERE payment_number=#{paymentNumber} FOR UPDATE")
    StoredPayment lockPayment(String paymentNumber);

    @Insert("""
            INSERT INTO simulated_payment (payment_number, merchant_order_number, amount, payment_expires_at,
                payment_status, accepted_at, result_at, failure_code, created_at, updated_at)
            VALUES (#{binding.paymentNumber}, #{binding.merchantOrderNumber}, #{binding.amount},
                #{binding.paymentExpiresAt}, 'RESERVED', NULL, NULL, NULL, #{at}, #{at})
            """)
    int insertReserved(@Param("binding") Binding binding, @Param("at") Instant at);

    @Update("""
            UPDATE simulated_payment
            SET payment_status=#{status}, accepted_at=#{acceptedAt}, result_at=#{resultAt},
                failure_code=#{failureCode}, updated_at=#{at}
            WHERE payment_number=#{paymentNumber} AND payment_status='RESERVED'
            """)
    int initializePayment(@Param("paymentNumber") String paymentNumber, @Param("status") StoredStatus status,
                          @Param("acceptedAt") Instant acceptedAt, @Param("resultAt") Instant resultAt,
                          @Param("failureCode") String failureCode, @Param("at") Instant at);

    @Update("""
            UPDATE simulated_payment SET payment_status=#{status}, result_at=#{at},
                failure_code=#{failureCode}, updated_at=#{at}
            WHERE payment_number=#{paymentNumber} AND payment_status='PROCESSING'
            """)
    int finishPayment(@Param("paymentNumber") String paymentNumber, @Param("status") StoredStatus status,
                      @Param("at") Instant at, @Param("failureCode") String failureCode);

    @Insert("""
            INSERT INTO simulated_notification (notification_number, payment_number, observed_status,
                observed_at, delivered_at)
            VALUES (#{notificationNumber}, #{paymentNumber}, #{observedStatus}, #{observedAt}, NULL)
            """)
    int insertNotification(Notification notification);

    @Select("""
            SELECT * FROM simulated_notification WHERE payment_number=#{paymentNumber}
            ORDER BY observed_at, notification_number
            """)
    List<Notification> notifications(String paymentNumber);

    @Select("SELECT * FROM simulated_notification WHERE notification_number=#{notificationNumber}")
    Notification notification(String notificationNumber);

    @Update("""
            UPDATE simulated_notification SET delivered_at=GREATEST(#{at}, observed_at)
            WHERE notification_number=#{notificationNumber} AND delivered_at IS NULL
            """)
    int acknowledgeNotification(@Param("notificationNumber") String notificationNumber, @Param("at") Instant at);
}
