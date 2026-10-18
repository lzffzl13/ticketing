package com.ticketing.trade;

import com.ticketing.checkin.CheckinService;
import com.ticketing.common.web.BusinessException;
import com.ticketing.consistency.ConsistencyModels.SessionReport;
import com.ticketing.consistency.ConsistencyScanner;
import com.ticketing.consistency.ConsistencyService;
import com.ticketing.simulator.RefundSimulatorService;
import com.ticketing.support.Week2Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.ticketing.checkin.CheckinModels.Result.*;
import static com.ticketing.trade.TradeModels.SelectionMode.*;
import static com.ticketing.trade.TradeRequests.CreateOrder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({PaymentTestSupport.TestClock.class, PaymentTestSupport.RealMapperSpies.class,
        ConsistencyIT.RealConsistencyMapperSpies.class})
class ConsistencyIT extends RefundTestSupport {
    @Autowired private ConsistencyService consistency;
    @Autowired private RefundService refunds;
    @Autowired private RefundSimulatorService refundChannel;
    @Autowired private CheckinService checkins;
    @Autowired private TicketCredentialService credentials;
    @Autowired @Qualifier("consistencyMapper") private Object consistencyMapper;

    @BeforeEach
    void resetConsistencyMapper() { reset(consistencyMapper); }

    @Test
    void paidAndNormallyPendingWorkAreConsistentAndRecoveryInformationIsSeparate() {
        var paid = healthy();
        assertEquals(1, paid.counts().orders()); assertEquals(2, paid.counts().tickets());
        var pending = newOrder("consistency-pending", "FLOOR-A", "B");
        payment.start(consumer1, pending.id(), "consistency-pending-payment");
        var report = healthy();
        assertEquals(2, report.counts().orders()); assertEquals(1, report.recovery().pendingOrders());
        assertEquals(1, report.recovery().unresolvedPayments());
        assertEquals(0, report.recovery().manualOrders());
    }

    @Test
    void independentChannelSuccessIsNotReportedAsAHalfCommittedLocalRefund() {
        var refund = refunds.start(consumer1, order.id(), paidTickets.getFirst().id(), "consistency-channel-window");
        assertEquals("PROCESSING", refund.refundStatus().name());
        refundChannel.succeed(refund.refundNumber());
        var before = businessState();
        var report = healthy();
        assertEquals(1, report.recovery().unresolvedRefunds());
        assertEquals(before, businessState(), "inspection must not deliver a notice or mutate any local/channel fact");
        finishRefund(refund, "SUCCEEDED");
        assertEquals(0, healthy().recovery().unresolvedRefunds());
    }

    @Test
    void refundResaleAndRealCheckinKeepHistoricalTicketsSeparateFromCurrentOwnership() {
        var old = paidTickets.getFirst();
        String oldCode = credentials.credential(consumer1, order.id(), old.id()).credential();
        var refund = refunds.start(consumer1, order.id(), old.id(), "consistency-resale-refund");
        finishRefund(refund, "SUCCEEDED");
        healthy();
        var replacement = orders.create(consumer2, "consistency-replacement",
                new CreateOrder(sessionId, MANUAL, List.of(old.sessionSeatId()))).order();
        healthy(); // Historical SUCCEEDED refund must allow the seat to be HELD by the replacement.
        var attempt = payment.start(consumer2, replacement.id(), "consistency-replacement-pay");
        payment.applyChannel(simulator.succeed(attempt.paymentNumber()));
        healthy();
        var newTicket = payment.tickets(consumer2, replacement.id()).getFirst();
        clock.set(sessions.get(sessionId).admissionOpenAt());
        long checker = checkerId();
        assertEquals(REFUNDED, checkins.scan(checker, sessionId, oldCode, null).result());
        var remaining = paidTickets.getLast();
        assertEquals(SUCCESS, checkins.scan(checker, sessionId,
                credentials.credential(consumer1, order.id(), remaining.id()).credential(), null).result());
        var code = credentials.credential(consumer2, replacement.id(), newTicket.id()).credential();
        assertEquals(SUCCESS, checkins.scan(checker, sessionId, code, null).result());
        assertEquals(ALREADY_CHECKED_IN, checkins.scan(checker, sessionId, code, null).result());
        var report = healthy();
        assertEquals(2, report.counts().orders()); assertEquals(3, report.counts().tickets());
        assertEquals(1, report.counts().refunds()); assertEquals(2, report.counts().successfulCheckins());
        assertEquals("COMPLETED", orders.get(consumer1, order.id()).orderStatus().name());
        assertEquals("COMPLETED", orders.get(consumer2, replacement.id()).orderStatus().name());
    }

    @Test
    void cancelledHistoricalOrderDoesNotDemandThatReusedSeatsStayAvailable() {
        var cancelled = newOrder("consistency-cancelled", "FLOOR-A", "B");
        payment.cancel(consumer1, cancelled.id());
        healthy();
        var replacement = newOrder("consistency-after-cancel", "FLOOR-A", "B");
        healthy();
        var attempt = payment.start(consumer1, replacement.id(), "consistency-after-cancel-pay");
        payment.applyChannel(simulator.succeed(attempt.paymentNumber()));
        healthy();
        assertEquals("CANCELLED", orders.get(consumer1, cancelled.id()).orderStatus().name());
    }

    @Test
    void failedRefundHistoryAllowsLaterRefundAndSuccessfulCheckin() {
        var first = refunds.start(consumer1, order.id(), paidTickets.getFirst().id(), "consistency-failed");
        finishRefund(first, "FAILED"); healthy();
        var retry = refunds.start(consumer1, order.id(), paidTickets.getFirst().id(), "consistency-retry");
        healthy(); finishRefund(retry, "SUCCEEDED"); healthy();
        clock.set(sessions.get(sessionId).admissionOpenAt());
        var remaining = paidTickets.getLast();
        assertEquals(SUCCESS, checkins.scan(checkerId(), sessionId,
                credentials.credential(consumer1, order.id(), remaining.id()).credential(), null).result());
        healthy();
    }

    enum Fault { ORDER_TOTAL, HOLD_DETAIL, MISSING_TICKET, AVAILABLE_SEAT, REFUNDING_WITHOUT_REFUND,
        EARLY_COMPLETED, CHECKED_WITHOUT_AUDIT, PAYMENT_AMOUNT, REFUND_AMOUNT, REFUND_SUMMARY, HELD_BINDING,
        SUCCESS_WITH_VALID_TICKET, FULFILLED_ORDER_REOPENED }

    @ParameterizedTest
    @EnumSource(Fault.class)
    void explicitSqlFaultsAreReportedWithIdentitiesWithoutChangingAnyBusinessFact(Fault fault) throws Exception {
        String expected = switch (fault) {
            case ORDER_TOTAL -> {
                jdbc.update("UPDATE customer_order SET total_amount=total_amount+1 WHERE id=?", order.id()); yield "C01_ORDER_STRUCTURE";
            }
            case HOLD_DETAIL -> {
                jdbc.update("DELETE FROM seat_hold_item WHERE hold_id=? AND session_seat_id=?", order.hold().id(), paidTickets.getFirst().sessionSeatId());
                yield "C01_ORDER_STRUCTURE";
            }
            case MISSING_TICKET -> {
                jdbc.update("DELETE FROM ticket WHERE id=?", paidTickets.getFirst().id()); yield "C04_PAID_FULFILLMENT";
            }
            case AVAILABLE_SEAT -> {
                jdbc.update("UPDATE session_seat SET seat_status='AVAILABLE' WHERE id=?", paidTickets.getFirst().sessionSeatId()); yield "C05_SEAT_TICKET";
            }
            case REFUNDING_WITHOUT_REFUND -> {
                jdbc.update("UPDATE ticket SET ticket_status='REFUNDING' WHERE id=?", paidTickets.getFirst().id()); yield "C07_TICKET_REFUND";
            }
            case EARLY_COMPLETED -> {
                jdbc.update("UPDATE customer_order SET order_status='COMPLETED' WHERE id=?", order.id()); yield "C08_REFUND_SUMMARY";
            }
            case CHECKED_WITHOUT_AUDIT -> {
                jdbc.update("UPDATE ticket SET ticket_status='CHECKED_IN' WHERE id=?", paidTickets.getFirst().id()); yield "C09_CHECKIN_AUDIT";
            }
            case PAYMENT_AMOUNT -> {
                jdbc.update("UPDATE payment_record SET amount=amount+1 WHERE id=?", originalPayment.id()); yield "C04_PAYMENT_BINDING";
            }
            case REFUND_AMOUNT -> {
                var refund = refunds.start(consumer1, order.id(), paidTickets.getFirst().id(), "consistency-amount-fault");
                jdbc.update("UPDATE refund_record SET amount=amount+1 WHERE id=?", refund.id()); yield "C07_REFUND_BINDING";
            }
            case REFUND_SUMMARY -> {
                var refund = refunds.start(consumer1, order.id(), paidTickets.getFirst().id(), "consistency-summary-fault");
                finishRefund(refund, "SUCCEEDED");
                jdbc.update("UPDATE customer_order SET order_status='PAID' WHERE id=?", order.id()); yield "C08_REFUND_SUMMARY";
            }
            case HELD_BINDING -> {
                var pending = newOrder("consistency-hold-fault", "FLOOR-A", "B");
                jdbc.update("UPDATE seat_hold SET hold_status='CONSUMED' WHERE id=?", pending.hold().id()); yield "C06_HELD_BINDING";
            }
            case SUCCESS_WITH_VALID_TICKET -> {
                jdbc.update("""
                        INSERT INTO checkin_record(request_id,checker_id,submitted_session_id,session_id,ticket_id,credential_digest,attempted_at,result)
                        VALUES(?,?,?,?,?,?,?,'SUCCESS')
                        """, java.util.UUID.randomUUID().toString(), checkerId(), sessionId, sessionId,
                        paidTickets.getFirst().id(), "0".repeat(64), java.sql.Timestamp.from(clock.instant()));
                yield "C09_CHECKIN_AUDIT";
            }
            case FULFILLED_ORDER_REOPENED -> {
                clock.set(sessions.get(sessionId).admissionOpenAt());
                for (var ticket : paidTickets) {
                    assertEquals(SUCCESS, checkins.scan(checkerId(), sessionId,
                            credentials.credential(consumer1, order.id(), ticket.id()).credential(), null).result());
                }
                assertEquals("COMPLETED", orders.get(consumer1, order.id()).orderStatus().name());
                jdbc.update("UPDATE customer_order SET order_status='PAID' WHERE id=?", order.id());
                yield "C08_REFUND_SUMMARY";
            }
        };
        var before = businessState();
        var report = consistency.inspect(sessionId);
        assertFalse(report.consistent());
        assertTrue(report.differences().stream().anyMatch(d -> d.rule().equals(expected)), report.differences().toString());
        assertTrue(report.differences().stream().allMatch(d -> d.entityId() > 0 && !d.observed().isBlank() && !d.recommendation().isBlank()));
        assertEquals(before, businessState(), "a fault report must preserve both local and independently committed channel evidence");
        if (fault == Fault.ORDER_TOTAL) {
            var sample = java.nio.file.Path.of("target", "week6-consistency-difference.json");
            java.nio.file.Files.createDirectories(sample.getParent());
            java.nio.file.Files.writeString(sample, json.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test
    void manualRecoveryCountsDoNotTurnKnownUnresolvedWorkIntoLocalCorruption() {
        var pending = newOrder("consistency-manual", "FLOOR-A", "B");
        var refund = refunds.start(consumer1, order.id(), paidTickets.getFirst().id(), "consistency-manual-refund");
        jdbc.update("UPDATE customer_order SET manual_required=TRUE WHERE id=?", pending.id());
        jdbc.update("UPDATE refund_record SET manual_required=TRUE WHERE id=?", refund.id());
        var report = healthy();
        assertEquals(1, report.recovery().pendingOrders()); assertEquals(1, report.recovery().manualOrders());
        assertEquals(1, report.recovery().unresolvedRefunds()); assertEquals(1, report.recovery().manualRefunds());
    }

    @Test
    void snapshotDoesNotCombinePrePaymentOrderWithPostPaymentTicketsAcrossAConcurrentCommit() throws Exception {
        var pending = newOrder("consistency-snapshot-payment", "FLOOR-A", "B");
        var attempt = payment.start(consumer1, pending.id(), "consistency-snapshot-pay");
        var channel = simulator.succeed(attempt.paymentNumber());
        var readOrders = new CountDownLatch(1); var resume = new CountDownLatch(1);
        pauseAfterActualOrdersRead(readOrders, resume);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var reportFuture = pool.submit(() -> consistency.inspect(sessionId));
            try {
                assertTrue(readOrders.await(10, TimeUnit.SECONDS));
                payment.applyChannel(channel); // Commits while the read transaction remains open.
            } finally { resume.countDown(); }
            var report = reportFuture.get(20, TimeUnit.SECONDS);
            assertTrue(report.consistent(), report.differences().toString());
            assertEquals(2, report.counts().tickets(), "all table reads must retain the pre-payment snapshot");
            assertEquals(1, report.recovery().pendingOrders());
        } finally { resume.countDown(); }
        assertEquals(4, healthy().counts().tickets(), "the next report sees the entire committed payment result");
    }

    @Test
    void snapshotDoesNotCombinePreRefundOrderWithPostRefundTicketAndSeatAcrossACommit() throws Exception {
        var refund = refunds.start(consumer1, order.id(), paidTickets.getFirst().id(), "consistency-snapshot-refund");
        refundChannel.succeed(refund.refundNumber());
        var readOrders = new CountDownLatch(1); var resume = new CountDownLatch(1);
        pauseAfterActualOrdersRead(readOrders, resume);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var reportFuture = pool.submit(() -> consistency.inspect(sessionId));
            try {
                assertTrue(readOrders.await(10, TimeUnit.SECONDS));
                finishRefund(refund, "SUCCEEDED");
            } finally { resume.countDown(); }
            var report = reportFuture.get(20, TimeUnit.SECONDS);
            assertTrue(report.consistent(), report.differences().toString());
            assertEquals(1, report.recovery().unresolvedRefunds(), "the first report must retain the original REFUNDING/PROCESSING facts");
        } finally { resume.countDown(); }
        assertEquals(0, healthy().recovery().unresolvedRefunds());
    }

    @Test
    void operationsEndpointEnforcesRoleAndReturnsOnlySafeReportFields() throws Exception {
        String path = "/api/operations/sessions/" + sessionId + "/consistency";
        assertEquals(401, refundHttp("GET", path, null, null).statusCode());
        assertEquals(403, refundHttp("GET", path, ownerToken, null).statusCode());
        assertEquals(403, refundHttp("GET", path, checkerToken, null).statusCode());
        var response = refundHttp("GET", path, operatorToken, null);
        var report = data(response);
        assertTrue(report.path("consistent").asBoolean()); assertEquals(0, report.path("differences").size());
        assertEquals(sessionId, report.path("sessionId").asLong());
        assertFalse(response.body().contains("recoveryToken")); assertFalse(response.body().contains("credential"));
        assertFalse(response.body().contains("paymentNumber"));
        var missing = assertThrows(BusinessException.class, () -> consistency.inspect(Long.MAX_VALUE));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatus());
        assertEquals(404, refundHttp("GET", "/api/operations/sessions/" + Long.MAX_VALUE + "/consistency", operatorToken, null).statusCode());
    }

    @Test
    void actualScannerVisitsBoundedSessionsWrapsAndDoesNotWriteBusinessFacts() {
        sessions.create(Week2Fixtures.request("SESSION-CONSISTENCY-SECOND", fixture));
        var scanner = new ConsistencyScanner(consistency, 1);
        var before = businessState();
        assertEquals(1, scanner.scanOnce()); assertEquals(1, scanner.scanOnce()); assertEquals(1, scanner.scanOnce());
        assertEquals(before, businessState());
    }

    private SessionReport healthy() {
        var report = consistency.inspect(sessionId);
        assertTrue(report.consistent(), report.differences().toString());
        assertTrue(report.differences().isEmpty());
        return report;
    }

    private long checkerId() { return jdbc.queryForObject("SELECT id FROM account WHERE login_name='checker1'", Long.class); }

    private void finishRefund(RefundModels.RefundRecord refund, String status) {
        if (status.equals("SUCCEEDED")) { refundChannel.succeed(refund.refundNumber()); }
        else { refundChannel.fail(refund.refundNumber()); }
        var notification = refundChannel.notifications(refund.refundNumber()).stream()
                .filter(n -> n.refund().refundStatus().name().equals(status)).findFirst().orElseThrow();
        refunds.deliver(notification.notificationNumber());
    }

    private Map<String, List<Map<String, Object>>> businessState() {
        var state = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (var table : List.of("customer_order", "order_item", "seat_hold", "seat_hold_item", "session_seat", "payment_record",
                "ticket", "refund_record", "checkin_record", "simulated_payment", "simulated_notification", "simulator_refund", "simulator_refund_notification")) {
            state.put(table, jdbc.queryForList("SELECT * FROM " + table));
        }
        return state;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void pauseAfterActualOrdersRead(CountDownLatch readOrders, CountDownLatch resume) throws Exception {
        var type = Class.forName("com.ticketing.consistency.ConsistencyMapper");
        Method method = Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals("orders")).findFirst().orElseThrow();
        method.setAccessible(true);
        var paused = new AtomicBoolean();
        var stubbed = doAnswer(call -> {
            var result = method.invoke(sqlSession.getMapper((Class) type), call.getArguments());
            if (paused.compareAndSet(false, true)) {
                readOrders.countDown();
                assertTrue(resume.await(15, TimeUnit.SECONDS), "concurrent business commit must complete before the remaining snapshot reads");
            }
            return result;
        }).when(consistencyMapper);
        method.invoke(stubbed, anyLong());
    }

    @TestConfiguration
    static class RealConsistencyMapperSpies {
        @Bean
        static BeanPostProcessor consistencyFaultMapperSpy() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!name.equals("consistencyMapper")) { return bean; }
                    try {
                        return Class.forName("com.ticketing.consistency.ConsistencyMapper").isInstance(bean) ? spy(bean) : bean;
                    } catch (ClassNotFoundException missing) { throw new IllegalStateException(missing); }
                }
            };
        }
    }
}
