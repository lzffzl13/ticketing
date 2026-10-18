package com.ticketing.trade;

import com.ticketing.ticketing.TicketCredentialCodec;
import com.ticketing.support.Week2Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import tools.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.ticketing.trade.TradeModels.SelectionMode.MANUAL;
import static com.ticketing.trade.TradeRequests.CreateOrder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Purchases, refunds and scans use their real entry points; controlled time is limited to the test Clock. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({PaymentTestSupport.TestClock.class, PaymentTestSupport.RealMapperSpies.class, CheckinIT.AuditSpy.class})
class CheckinIT extends RefundTestSupport {
    @Autowired private TicketCredentialCodec codec;
    @Autowired @Qualifier("checkinMapper") private Object auditMapper;
    private String firstCode;
    private String secondCode;
    private long checkerId;

    @BeforeEach
    void prepareCheckin() {
        reset(auditMapper);
        firstCode = codec.encode(paidTickets.getFirst().ticketNumber());
        secondCode = codec.encode(paidTickets.getLast().ticketNumber());
        checkerId = jdbc.queryForObject("SELECT id FROM account WHERE login_name='checker1'", Long.class);
    }

    @Test
    void scansKeepSoldInventoryAndOnlyTheLastOutstandingTicketCompletesTheOrder() throws Exception {
        openAdmission();
        var seatBefore = jdbc.queryForList("SELECT * FROM session_seat WHERE session_id=? ORDER BY id", sessionId);
        var first = scan(firstCode);
        assertEquals("SUCCESS", first.path("result").asString());
        assertEquals("PAID", orderStatus());
        assertTicket(paidTickets.getFirst(), "CHECKED_IN");
        assertEquals("SUCCESS", scan(secondCode).path("result").asString());
        assertEquals("COMPLETED", orderStatus());
        assertEquals(seatBefore, jdbc.queryForList("SELECT * FROM session_seat WHERE session_id=? ORDER BY id", sessionId));
        assertEquals("CONSUMED", orders.get(consumer1, order.id()).hold().status().name());
        assertEquals(2, count("checkin_record"));
        assertEquals("ALREADY_CHECKED_IN", scan(firstCode).path("result").asString());
        assertEquals("COMPLETED", orderStatus());
    }

    @RepeatedTest(5)
    void twoEntrancesHaveOneSuccessAndIndependentAudits() throws Exception {
        openAdmission();
        var results = compete(() -> scan(firstCode).path("result").asString(),
                () -> scan(firstCode).path("result").asString());
        assertEquals(List.of("ALREADY_CHECKED_IN", "SUCCESS"), results.stream().sorted().toList());
        assertEquals(2, count("checkin_record"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(DISTINCT request_id) FROM checkin_record", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM checkin_record WHERE result='SUCCESS'", Integer.class));
        assertTicket(paidTickets.getFirst(), "CHECKED_IN");
    }

    @ParameterizedTest
    @CsvSource({"OPEN,-1,TOO_EARLY", "OPEN,0,SUCCESS", "CLOSE,-1,SUCCESS", "CLOSE,0,TOO_LATE"})
    void admissionUsesTheExactHalfOpenMicrosecondWindow(String boundary, long micros, String result) throws Exception {
        var session = sessions.get(sessionId);
        clock.set((boundary.equals("OPEN") ? session.admissionOpenAt() : session.admissionCloseAt()).plusNanos(micros * 1_000));
        refreshTokens();
        assertEquals(result, scan(firstCode).path("result").asString());
        assertTicket(paidTickets.getFirst(), result.equals("SUCCESS") ? "CHECKED_IN" : "VALID");
        assertEquals(1, count("checkin_record"));
    }

    @Test
    void unauthenticatedOrUnresolvableCredentialsAreAuditedWithoutLinkingAnUntrustedTicket() throws Exception {
        openAdmission();
        for (String invalid : List.of("forged", firstCode.substring(0, firstCode.length() - 1) + "!",
                codec.encode(UUID.randomUUID().toString()), firstCode.replace("TKT1.", "TKT9."))) {
            var result = scan(invalid);
            assertEquals("INVALID_CREDENTIAL", result.path("result").asString());
            assertTrue(result.path("ticketId").isNull());
            assertTrue(result.path("credentialDigest").asString().matches("[0-9a-f]{64}"));
            assertFalse(result.toString().contains(invalid));
        }
        assertEquals(4, count("checkin_record"));
        assertTicket(paidTickets.getFirst(), "VALID");
    }

    @Test
    void wrongAndMissingSessionsHaveExplicitAuditsAndNeverChangeTheTicket() throws Exception {
        var other = sessions.create(Week2Fixtures.request("OTHER-CHECKIN", fixture)).id();
        openAdmission();
        var wrong = scan(other, firstCode);
        assertEquals("WRONG_SESSION", wrong.path("result").asString());
        assertEquals(other.longValue(), wrong.path("sessionId").asLong());
        assertEquals(paidTickets.getFirst().id().longValue(), wrong.path("ticketId").asLong());
        var missing = scan(Long.MAX_VALUE, firstCode);
        assertEquals("SESSION_NOT_FOUND", missing.path("result").asString());
        assertTrue(missing.path("sessionId").isNull());
        assertTrue(missing.path("ticketId").isNull());
        assertEquals(Long.MAX_VALUE, missing.path("submittedSessionId").asLong());
        assertTicket(paidTickets.getFirst(), "VALID");
    }

    @Test
    void refundingAndRefundedTicketsRejectBeforeAnyAccessToTheirOldSeat() throws Exception {
        var refund = request(paidTickets.getFirst(), "before-admission");
        openAdmission();
        assertEquals("REFUNDING", scan(firstCode).path("result").asString());
        finish(refund, "SUCCEEDED");
        assertEquals("REFUNDED", scan(firstCode).path("result").asString());
        assertSeat(paidTickets.getFirst(), "AVAILABLE");
        assertTicket(paidTickets.getFirst(), "REFUNDED");
    }

    @Test
    void aFailedAcceptedRefundRestoresAdmissionButNoNewRefundAfterOpening() throws Exception {
        var refund = request(paidTickets.getFirst(), "will-fail");
        openAdmission();
        finish(refund, "FAILED");
        assertEquals("SUCCESS", scan(firstCode).path("result").asString());
        assertEquals(409, refundHttp("POST", requestPath(order.id(), paidTickets.getFirst().id()), ownerToken,
                Map.of("requestKey", "after-checkin")).statusCode());
        assertTicket(paidTickets.getFirst(), "CHECKED_IN");
        assertEquals(1, count("refund_record"));
    }

    @Test
    void refundSuccessAfterTheOtherTicketWasCheckedInCompletesTheOrderAndOldEventsDoNotUndoIt() throws Exception {
        var refund = request(paidTickets.getFirst(), "late-refund-result");
        openAdmission();
        assertEquals("SUCCESS", scan(secondCode).path("result").asString());
        assertEquals("PAID", orderStatus());
        finish(refund, "SUCCEEDED");
        assertEquals("COMPLETED", orderStatus());
        deliver(notice(refund, "SUCCEEDED"));
        deliver(notice(refund, "PROCESSING"));
        payment.applyChannel(simulator.succeed(originalPayment.paymentNumber()));
        assertEquals("COMPLETED", orderStatus());
        assertEquals("REFUNDED", scan(firstCode).path("result").asString());
        assertEquals("ALREADY_CHECKED_IN", scan(secondCode).path("result").asString());
    }

    @Test
    void lastCheckinAfterAPartialRefundCompletesTheOrder() throws Exception {
        var refund = request(paidTickets.getFirst(), "partial-first");
        finish(refund, "SUCCEEDED");
        openAdmission();
        assertEquals("SUCCESS", scan(secondCode).path("result").asString());
        assertEquals("COMPLETED", orderStatus());
    }

    @Test
    void anOldRefundedCodeCannotUseOrDisturbANewBuyersTicket() throws Exception {
        var old = paidTickets.getFirst();
        var refund = request(old, "resale");
        finish(refund, "SUCCEEDED");
        var resale = orders.create(consumer2, "checkin-resale", new CreateOrder(sessionId, MANUAL,
                List.of(old.sessionSeatId()))).order();
        assertEquals("REFUNDED", scan(firstCode).path("result").asString(), "old terminal result precedes time and HELD-seat checks");
        var attempt = payment.start(consumer2, resale.id(), "resale-payment");
        payment.applyChannel(simulator.succeed(attempt.paymentNumber()));
        var current = payment.tickets(consumer2, resale.id()).getFirst();
        String newCode = codec.encode(current.ticketNumber());
        openAdmission();
        assertEquals("REFUNDED", scan(firstCode).path("result").asString());
        assertTicket(current, "VALID");
        assertEquals("SUCCESS", scan(newCode).path("result").asString());
        assertSeat(current, "SOLD");
        assertEquals("COMPLETED", orders.get(consumer2, resale.id()).orderStatus().name());
        assertEquals("REFUNDED", scan(firstCode).path("result").asString());
    }

    @Test
    void aSuccessfulRefundNotificationRacingWithAScanCannotAdmitTheRefundingTicket() throws Exception {
        var refund = request(paidTickets.getFirst(), "race-success");
        terminal(refund, "SUCCEEDED");
        String success = notice(refund, "SUCCEEDED");
        openAdmission();
        var results = compete(() -> { deliver(success); return "DELIVERED"; },
                () -> scan(firstCode).path("result").asString());
        assertEquals("DELIVERED", results.getFirst());
        assertTrue(List.of("REFUNDING", "REFUNDED").contains(results.getLast()));
        assertTicket(paidTickets.getFirst(), "REFUNDED");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM checkin_record WHERE result='SUCCESS'", Integer.class));
    }

    @Test
    void aFailedRefundNotificationRacingWithAScanEitherRestoresAdmissionOrLeavesAnAuditedRetry() throws Exception {
        var refund = request(paidTickets.getFirst(), "race-failure");
        terminal(refund, "FAILED");
        String failure = notice(refund, "FAILED");
        openAdmission();
        var results = compete(() -> { deliver(failure); return "DELIVERED"; },
                () -> scan(firstCode).path("result").asString());
        assertTrue(List.of("REFUNDING", "SUCCESS").contains(results.getLast()));
        if (results.getLast().equals("REFUNDING")) { assertEquals("SUCCESS", scan(firstCode).path("result").asString()); }
        assertTicket(paidTickets.getFirst(), "CHECKED_IN");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM checkin_record WHERE result='SUCCESS'", Integer.class));
    }

    @Test
    void aScanWaitingForTheSessionLockUsesTimeAfterTheWait() throws Exception {
        var close = sessions.get(sessionId).admissionCloseAt();
        clock.set(close.minusNanos(1_000));
        refreshTokens();
        var held = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var target = AopTestUtils.<com.ticketing.catalog.CatalogPurchaseService>getUltimateTargetObject(purchase);
        doAnswer(call -> { entered.countDown(); return call.callRealMethod(); }).when(target).lockForCheckin(sessionId);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var holder = pool.submit(() -> transaction().execute(status -> {
                jdbc.queryForObject("SELECT id FROM session WHERE id=? FOR UPDATE", Long.class, sessionId);
                held.countDown(); await(release); return "RELEASED";
            }));
            assertTrue(held.await(10, TimeUnit.SECONDS));
            var requesting = pool.submit(() -> scan(firstCode));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                clock.set(close);
            } finally { release.countDown(); }
            assertEquals("RELEASED", holder.get(20, TimeUnit.SECONDS));
            assertEquals("TOO_LATE", requesting.get(20, TimeUnit.SECONDS).path("result").asString());
        } finally { release.countDown(); }
        assertTicket(paidTickets.getFirst(), "VALID");
        assertEquals(1, count("checkin_record"));
    }

    @Test
    void aRefundWaitingAcrossAdmissionOpeningIsRejectedWhileTheScanCanSucceed() throws Exception {
        var open = sessions.get(sessionId).admissionOpenAt();
        clock.set(open.minusNanos(1_000));
        refreshTokens();
        var held = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var target = AopTestUtils.<com.ticketing.catalog.CatalogPurchaseService>getUltimateTargetObject(purchase);
        doAnswer(call -> { entered.countDown(); return call.callRealMethod(); }).when(target).lockForPurchase(sessionId);
        try (var pool = Executors.newFixedThreadPool(3)) {
            var holder = pool.submit(() -> transaction().execute(status -> {
                jdbc.queryForObject("SELECT id FROM session WHERE id=? FOR UPDATE", Long.class, sessionId);
                held.countDown(); await(release); return "RELEASED";
            }));
            assertTrue(held.await(10, TimeUnit.SECONDS));
            var refund = pool.submit(() -> refundHttp("POST", requestPath(order.id(), paidTickets.getFirst().id()),
                    ownerToken, Map.of("requestKey", "waits-until-open")));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            clock.set(open);
            var scanning = pool.submit(() -> scan(firstCode));
            release.countDown();
            assertEquals("RELEASED", holder.get(20, TimeUnit.SECONDS));
            assertEquals(409, refund.get(20, TimeUnit.SECONDS).statusCode());
            assertEquals("SUCCESS", scanning.get(20, TimeUnit.SECONDS).path("result").asString());
        } finally { release.countDown(); }
        assertTicket(paidTickets.getFirst(), "CHECKED_IN");
        assertEquals(0, count("refund_record"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void aFaultAfterTheActualSuccessAuditInsertRollsBackTheTicketAndTheOrder() throws Exception {
        openAdmission();
        scan(firstCode);
        var type = Class.forName("com.ticketing.checkin.CheckinMapper");
        var insert = Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals("insert")).findFirst().orElseThrow();
        insert.setAccessible(true);
        var stubbed = doAnswer(call -> {
            int changed = (int) insert.invoke(sqlSession.getMapper((Class) type), call.getArguments());
            assertEquals(1, changed);
            throw new IllegalStateException("test fault after actual check-in audit INSERT");
        }).when(auditMapper);
        insert.invoke(stubbed, any(), any());
        var failed = refundHttp("POST", "/api/checkins", checkerToken,
                Map.of("sessionId", sessionId, "credential", secondCode));
        assertEquals(500, failed.statusCode(), failed.body());
        assertEquals(1, count("checkin_record"));
        assertTicket(paidTickets.getLast(), "VALID");
        assertEquals("PAID", orderStatus());
        reset(auditMapper);
        assertEquals("SUCCESS", scan(secondCode).path("result").asString());
        assertEquals("COMPLETED", orderStatus());
    }

    @Test
    void databaseConstraintRejectsASecondSuccessForTheSameTicket() throws Exception {
        openAdmission();
        scan(firstCode);
        assertSqlCode(1062, () -> jdbc.update("""
                INSERT INTO checkin_record(request_id,checker_id,submitted_session_id,session_id,ticket_id,
                    credential_digest,attempted_at,result)
                SELECT ?,checker_id,submitted_session_id,session_id,ticket_id,credential_digest,attempted_at,result
                FROM checkin_record WHERE result='SUCCESS'
                """, UUID.randomUUID().toString()));
        assertEquals(1, count("checkin_record"));
    }

    @Test
    void inconsistentInventoryOrUnpaidOrderIsAuditedWithoutChangingTheTicket() throws Exception {
        openAdmission();
        // Explicit corruption fixtures in ticketing_test; they do not represent normal business flows.
        jdbc.update("UPDATE session_seat SET seat_status='AVAILABLE' WHERE id=?", paidTickets.getFirst().sessionSeatId());
        assertEquals("INCONSISTENT_STATE", scan(firstCode).path("result").asString());
        jdbc.update("UPDATE customer_order SET order_status='CANCELLED' WHERE id=?", order.id());
        assertEquals("INCONSISTENT_STATE", scan(secondCode).path("result").asString());
        assertTicket(paidTickets.getFirst(), "VALID");
        assertTicket(paidTickets.getLast(), "VALID");
        assertEquals(2, count("checkin_record"));
    }

    @Test
    void roleAndFormatErrorsAreOutsideTheAcceptedScanAuditBoundary() throws Exception {
        openAdmission();
        var body = Map.of("sessionId", sessionId, "credential", firstCode);
        assertEquals(401, refundHttp("POST", "/api/checkins", null, body).statusCode());
        for (String token : List.of(ownerToken, operatorToken)) {
            assertEquals(403, refundHttp("POST", "/api/checkins", token, body).statusCode());
        }
        for (var invalid : List.of(Map.of("sessionId", 0, "credential", firstCode),
                Map.of("sessionId", sessionId, "credential", ""),
                Map.of("sessionId", sessionId, "credential", "x".repeat(257)))) {
            assertEquals(400, refundHttp("POST", "/api/checkins", checkerToken, invalid).statusCode());
        }
        assertEquals(0, count("checkin_record"));
        assertEquals(403, refundHttp("GET", "/api/checkins", ownerToken, null).statusCode());
        assertEquals(200, refundHttp("GET", "/api/checkins", operatorToken, null).statusCode());
    }

    @Test
    void auditQueriesFilterAndPageWithoutReturningCredentials() throws Exception {
        openAdmission();
        var success = scan(firstCode);
        scan(firstCode);
        scan(secondCode);
        var page = data(refundHttp("GET", "/api/checkins?sessionId=" + sessionId + "&limit=1", checkerToken, null));
        assertEquals(1, page.path("records").size());
        assertEquals(success.path("id").asLong(), page.path("records").get(0).path("id").asLong());
        assertEquals(success.path("id").asLong(), page.path("nextAfterId").asLong());
        var next = data(refundHttp("GET", "/api/checkins?afterId=" + page.path("nextAfterId").asLong(), operatorToken, null));
        assertEquals(2, next.path("records").size());
        assertTrue(next.path("nextAfterId").isNull());
        var byTicket = data(refundHttp("GET", "/api/checkins?ticketId=" + paidTickets.getFirst().id(), checkerToken, null));
        assertEquals(2, byTicket.path("records").size());
        var at = sessions.get(sessionId).admissionOpenAt();
        assertEquals(3, data(refundHttp("GET", "/api/checkins?from=" + at + "&to=" + at.plusSeconds(1), checkerToken, null)).path("records").size());
        assertEquals(0, data(refundHttp("GET", "/api/checkins?to=" + at, checkerToken, null)).path("records").size());
        assertFalse(byTicket.toString().contains(firstCode));
        assertFalse(byTicket.toString().contains("recoveryToken"));
        for (String query : List.of("limit=101", "limit=0", "afterId=-1", "sessionId=0", "from=" + at + "&to=" + at)) {
            assertEquals(400, refundHttp("GET", "/api/checkins?" + query, checkerToken, null).statusCode());
        }
        var response = refundHttp("POST", "/api/checkins", checkerToken, Map.of("sessionId", sessionId, "credential", firstCode));
        assertEquals(response.headers().firstValue("X-Request-Id").orElseThrow(), data(response).path("requestId").asString());
    }

    private void openAdmission() throws Exception {
        clock.set(sessions.get(sessionId).admissionOpenAt());
        refreshTokens();
    }
    private JsonNode scan(String credential) throws Exception { return scan(sessionId, credential); }
    private JsonNode scan(long selectedSession, String credential) throws Exception {
        return data(refundHttp("POST", "/api/checkins", checkerToken, Map.of("sessionId", selectedSession, "credential", credential)));
    }
    private String orderStatus() {
        return jdbc.queryForObject("SELECT order_status FROM customer_order WHERE id=?", String.class, order.id());
    }

    @TestConfiguration
    static class AuditSpy {
        @Bean
        static BeanPostProcessor checkinFaultMapperSpy() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!name.equals("checkinMapper")) { return bean; }
                    try { return Class.forName("com.ticketing.checkin.CheckinMapper").isInstance(bean) ? spy(bean) : bean; }
                    catch (ClassNotFoundException absent) { throw new IllegalStateException(absent); }
                }
            };
        }
    }
}
