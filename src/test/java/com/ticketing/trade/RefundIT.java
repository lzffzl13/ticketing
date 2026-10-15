package com.ticketing.trade;

import com.ticketing.catalog.CatalogPurchaseService;
import com.ticketing.simulator.RefundSimulatorService;
import com.ticketing.ticketing.TicketCredentialCodec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.ticketing.trade.TradeModels.SelectionMode.MANUAL;
import static com.ticketing.trade.TradeRequests.CreateOrder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/** HTTP refunds use real purchases and notifications; only CHECKED_IN is an explicit future-check-in fixture. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({PaymentTestSupport.TestClock.class, PaymentTestSupport.RealMapperSpies.class})
class RefundIT extends RefundTestSupport {
    @Autowired private TicketCredentialService credentials;
    @Autowired private TicketCredentialCodec codec;
    @Autowired private RefundService refunds;
    @MockitoSpyBean private RefundSimulatorService refundSimulator;

    @Test
    void oneTicketRefundKeepsTheOtherTicketAndOriginalPaymentThenTheSecondRefundCompletesTheOrder()
            throws Exception {
        var first = paidTickets.getFirst();
        var second = paidTickets.getLast();
        var paymentBefore = jdbc.queryForMap("SELECT * FROM payment_record WHERE id=?", originalPayment.id());
        var holdBefore = jdbc.queryForMap("SELECT * FROM seat_hold WHERE id=?", order.hold().id());
        var untouchedTicket = jdbc.queryForMap("SELECT * FROM ticket WHERE id=?", second.id());
        var untouchedSeat = jdbc.queryForMap("SELECT * FROM session_seat WHERE id=?", second.sessionSeatId());

        var refund = request(first, "single-ticket");
        assertEquals(first.id().longValue(), refund.path("ticketId").asLong());
        assertEquals(order.id().longValue(), refund.path("orderId").asLong());
        var item = order.items().stream().filter(i -> i.id().equals(first.orderItemId())).findFirst().orElseThrow();
        assertEquals(0, item.transactionPrice().compareTo(refund.path("amount").decimalValue()));
        assertTicket(first, "REFUNDING");
        assertSeat(first, "SOLD");
        assertEquals("PAID", orderRefunds().path("orderStatus").asString());
        assertEquals(0, BigDecimal.ZERO.compareTo(orderRefunds().path("refundedAmount").decimalValue()));

        terminal(refund, "SUCCEEDED");
        assertTicket(first, "REFUNDING");
        assertSeat(first, "SOLD");
        deliver(notice(refund, "SUCCEEDED"));

        assertTicket(first, "REFUNDED");
        assertSeat(first, "AVAILABLE");
        var partial = orderRefunds();
        assertEquals("PARTIALLY_REFUNDED", partial.path("orderStatus").asString());
        assertEquals(0, item.transactionPrice().compareTo(partial.path("refundedAmount").decimalValue()));
        assertEquals(untouchedTicket, jdbc.queryForMap("SELECT * FROM ticket WHERE id=?", second.id()));
        assertEquals(untouchedSeat, jdbc.queryForMap("SELECT * FROM session_seat WHERE id=?", second.sessionSeatId()));
        assertEquals(holdBefore, jdbc.queryForMap("SELECT * FROM seat_hold WHERE id=?", order.hold().id()));
        assertEquals(paymentBefore, jdbc.queryForMap("SELECT * FROM payment_record WHERE id=?", originalPayment.id()));

        finish(request(second, "remaining-ticket"), "SUCCEEDED");
        var fully = orderRefunds();
        assertEquals("FULLY_REFUNDED", fully.path("orderStatus").asString());
        assertEquals(0, order.totalAmount().compareTo(fully.path("refundedAmount").decimalValue()));
        assertEquals(2, fully.path("refunds").size());
        assertEquals(2, count("ticket"), "refunds must not issue replacement tickets");
        assertEquals(paymentBefore, jdbc.queryForMap("SELECT * FROM payment_record WHERE id=?", originalPayment.id()));
        assertEquals("CONSUMED", orders.get(consumer1, order.id()).hold().status().name());
    }

    @Test
    void sameCustomerRequestKeyReplaysTheOriginalAndCannotBeReboundToAnotherTicketOrOrder() throws Exception {
        var first = request(paidTickets.getFirst(), "fixed-request");
        var replay = request(paidTickets.getFirst(), "fixed-request");
        assertEquals(first.path("id").asLong(), replay.path("id").asLong());
        assertEquals(first.path("refundNumber").asString(), replay.path("refundNumber").asString());
        var conflict = refundHttp("POST", requestPath(order.id(), paidTickets.getLast().id()), ownerToken,
                Map.of("requestKey", "fixed-request"));
        assertEquals(409, conflict.statusCode(), conflict.body());

        var otherOrder = newOrder("refund-other-order", "FLOOR-A", "B");
        var otherPayment = payment.start(consumer1, otherOrder.id(), "refund-other-payment");
        payment.applyChannel(simulator.succeed(otherPayment.paymentNumber()));
        var otherTicket = payment.tickets(consumer1, otherOrder.id()).getFirst();
        var changedOrder = refundHttp("POST", requestPath(otherOrder.id(), otherTicket.id()), ownerToken,
                Map.of("requestKey", "fixed-request"));
        assertEquals(409, changedOrder.statusCode(), changedOrder.body());
        assertEquals(1, count("refund_record"));
        assertTicket(paidTickets.getLast(), "VALID");
        assertTicket(otherTicket, "VALID");
        assertEquals(first.path("id").asLong(), getRefund(first).path("id").asLong());

        var otherSeats = sessions.listSeats(sessionId).stream()
                .filter(s -> s.areaCodeSnapshot().equals("BALCONY-B") && s.rowLabelSnapshot().equals("D")
                        && s.seatNumberSnapshot() <= 2).map(s -> s.id()).toList();
        var strangersOrder = orders.create(consumer2, "other-customer-order",
                new CreateOrder(sessionId, MANUAL, otherSeats)).order();
        var strangersPayment = payment.start(consumer2, strangersOrder.id(), "other-customer-payment");
        payment.applyChannel(simulator.succeed(strangersPayment.paymentNumber()));
        var strangersTicket = payment.tickets(consumer2, strangersOrder.id()).getFirst();
        var independentlyOwned = data(refundHttp("POST", requestPath(strangersOrder.id(), strangersTicket.id()),
                otherToken, Map.of("requestKey", "fixed-request")));
        assertNotEquals(first.path("id").asLong(), independentlyOwned.path("id").asLong(),
                "request identity is scoped to a customer, not shared by all buyers");
        assertEquals(2, count("refund_record"));
    }

    @Test
    void failedRefundRestoresTheSameTicketAndOnlyANewKeyCreatesANewAttemptWhileOldNoticesStayHarmless()
            throws Exception {
        var ticket = paidTickets.getFirst();
        var originalCredential = credentials.credential(consumer1, order.id(), ticket.id());
        var first = request(ticket, "failed-original");
        var processingNotice = notice(first, "PROCESSING");
        finish(first, "FAILED");
        var failedNotice = notice(first, "FAILED");
        assertTicket(ticket, "VALID");
        assertSeat(ticket, "SOLD");
        assertEquals(originalCredential, credentials.credential(consumer1, order.id(), ticket.id()));
        assertEquals("PAID", orderRefunds().path("orderStatus").asString());
        assertEquals("FAILED", request(ticket, "failed-original").path("refundStatus").asString());
        assertEquals(1, count("refund_record"));

        var replacement = request(ticket, "new-intent");
        assertNotEquals(first.path("id").asLong(), replacement.path("id").asLong());
        assertNotEquals(first.path("refundNumber").asString(), replacement.path("refundNumber").asString());
        var replacementBefore = getRefund(replacement);
        deliver(failedNotice);
        deliver(processingNotice);
        assertEquals(replacementBefore, getRefund(replacement));
        assertTicket(ticket, "REFUNDING");
        assertSeat(ticket, "SOLD");
        finish(replacement, "SUCCEEDED");
        deliver(failedNotice);
        assertTicket(ticket, "REFUNDED");
        assertSeat(ticket, "AVAILABLE");
        assertEquals(2, count("refund_record"));
    }

    @Test
    void concurrentIdenticalKeysReturnOneRefundAndDifferentKeysForOneTicketAcceptOnlyOneIntent() throws Exception {
        var same = compete(() -> request(paidTickets.getFirst(), "same-concurrent"),
                () -> request(paidTickets.getFirst(), "same-concurrent"));
        assertEquals(same.getFirst().path("id").asLong(), same.getLast().path("id").asLong());
        var different = compete(() -> refundHttp("POST", requestPath(order.id(), paidTickets.getLast().id()),
                        ownerToken, Map.of("requestKey", "competing-a")),
                () -> refundHttp("POST", requestPath(order.id(), paidTickets.getLast().id()), ownerToken,
                        Map.of("requestKey", "competing-b")));
        assertEquals(1, different.stream().filter(r -> r.statusCode() >= 200 && r.statusCode() < 300).count());
        assertEquals(1, different.stream().filter(r -> r.statusCode() == 409).count());
        assertEquals(2, count("refund_record"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ticket WHERE order_id=? AND ticket_status='REFUNDING'",
                Integer.class, order.id()));
    }

    @Test
    void concurrentSuccessForBothTicketsRecomputesTheOrderSummaryWithoutLosingEitherRefund() throws Exception {
        var first = request(paidTickets.getFirst(), "both-first");
        var second = request(paidTickets.getLast(), "both-second");
        terminal(first, "SUCCEEDED");
        terminal(second, "SUCCEEDED");
        var firstNotice = notice(first, "SUCCEEDED");
        var secondNotice = notice(second, "SUCCEEDED");
        compete(() -> { deliver(firstNotice); return "first"; }, () -> { deliver(secondNotice); return "second"; });

        var current = orderRefunds();
        assertEquals("FULLY_REFUNDED", current.path("orderStatus").asString());
        assertEquals(0, order.totalAmount().compareTo(current.path("refundedAmount").decimalValue()));
        for (var ticket : paidTickets) { assertTicket(ticket, "REFUNDED"); assertSeat(ticket, "AVAILABLE"); }
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM refund_record WHERE refund_status='SUCCEEDED'",
                Integer.class));
    }

    @Test
    void consumerAndOperationsEndpointsEnforceRolesOwnershipAndExactOrderTicketRefundBindings() throws Exception {
        String target = requestPath(order.id(), paidTickets.getFirst().id());
        for (var denied : List.of(operatorToken, checkerToken)) {
            assertEquals(403, refundHttp("POST", target, denied, Map.of("requestKey", "forbidden")).statusCode());
        }
        assertEquals(401, refundHttp("POST", target, null, Map.of("requestKey", "anonymous")).statusCode());
        assertEquals(404, refundHttp("POST", target, otherToken, Map.of("requestKey", "foreign")).statusCode());
        assertEquals(404, refundHttp("POST", requestPath(Long.MAX_VALUE, paidTickets.getFirst().id()), ownerToken,
                Map.of("requestKey", "missing-order")).statusCode());
        assertEquals(404, refundHttp("POST", requestPath(order.id(), Long.MAX_VALUE), ownerToken,
                Map.of("requestKey", "missing-ticket")).statusCode());
        var otherOrder = newOrder("permission-other-order", "FLOOR-A", "B");
        assertEquals(404, refundHttp("POST", requestPath(otherOrder.id(), paidTickets.getFirst().id()), ownerToken,
                Map.of("requestKey", "wrong-order")).statusCode());
        assertEquals(0, count("refund_record"));

        var refund = request(paidTickets.getFirst(), "permission-owned");
        for (String readPath : List.of("/api/orders/" + order.id() + "/refunds",
                "/api/orders/" + order.id() + "/refunds/" + refund.path("id").asLong())) {
            assertEquals(401, refundHttp("GET", readPath, null, null).statusCode());
            assertEquals(403, refundHttp("GET", readPath, operatorToken, null).statusCode());
            assertEquals(403, refundHttp("GET", readPath, checkerToken, null).statusCode());
            assertEquals(404, refundHttp("GET", readPath, otherToken, null).statusCode());
        }
        assertEquals(404, refundHttp("GET", "/api/orders/" + otherOrder.id() + "/refunds/"
                + refund.path("id").asLong(), ownerToken, null).statusCode());
        assertEquals(404, refundHttp("GET", "/api/orders/" + order.id() + "/refunds/" + Long.MAX_VALUE,
                ownerToken, null).statusCode());
        String base = "/api/operations/refunds/" + refund.path("refundNumber").asString();
        String processing = notice(refund, "PROCESSING");
        for (var denied : List.of(ownerToken, otherToken, checkerToken)) {
            assertEquals(403, refundHttp("POST", base + "/succeed", denied, null).statusCode());
            assertEquals(403, refundHttp("POST", base + "/fail", denied, null).statusCode());
            assertEquals(403, refundHttp("GET", base + "/notifications", denied, null).statusCode());
            assertEquals(403, refundHttp("POST", "/api/operations/refund-notifications/" + processing + "/deliver",
                    denied, null).statusCode());
            assertEquals(403, refundHttp("POST", "/api/operations/refunds/" + refund.path("id").asLong()
                    + "/recover", denied, null).statusCode());
            assertEquals(403, refundHttp("GET", "/api/operations/refunds/" + refund.path("id").asLong()
                    + "/state", denied, null).statusCode());
        }
        assertEquals(401, refundHttp("POST", base + "/succeed", null, null).statusCode());
        assertEquals(200, refundHttp("GET", "/api/operations/refunds/" + refund.path("id").asLong() + "/state",
                operatorToken, null).statusCode());
        assertTicket(paidTickets.getFirst(), "REFUNDING");
        assertSeat(paidTickets.getFirst(), "SOLD");
    }

    @Test
    void invalidRequestKeysAndTheExplicitCheckedInFixtureCannotStartARefund() throws Exception {
        String path = requestPath(order.id(), paidTickets.getFirst().id());
        for (String key : List.of("", "space is invalid", "a".repeat(65))) {
            var invalid = refundHttp("POST", path, ownerToken, Map.of("requestKey", key));
            assertEquals(400, invalid.statusCode(), invalid.body());
        }
        assertEquals(400, refundHttp("POST", path, ownerToken, Map.of()).statusCode());
        // Check-in is scheduled for week six: this explicitly installs its terminal state, not a check-in API.
        jdbc.update("UPDATE ticket SET ticket_status='CHECKED_IN' WHERE id=?", paidTickets.getFirst().id());
        var checkedIn = refundHttp("POST", path, ownerToken, Map.of("requestKey", "checked-in"));
        assertEquals(409, checkedIn.statusCode(), checkedIn.body());
        assertEquals(0, count("refund_record"));
        assertTicket(paidTickets.getFirst(), "CHECKED_IN");
        assertSeat(paidTickets.getFirst(), "SOLD");
    }

    @Test
    void newRequestUsesStrictMicrosecondAdmissionOpenBoundaryButAnAcceptedOriginalCanFinishAndReplayAfterward()
            throws Exception {
        var open = sessions.get(sessionId).admissionOpenAt();
        clock.set(open.minusNanos(1_000));
        refreshTokens();
        var original = request(paidTickets.getFirst(), "last-microsecond");
        clock.set(open);
        var equal = refundHttp("POST", requestPath(order.id(), paidTickets.getLast().id()), ownerToken,
                Map.of("requestKey", "equal-is-too-late"));
        assertEquals(409, equal.statusCode(), equal.body());
        clock.set(open.plusNanos(1_000));
        var after = refundHttp("POST", requestPath(order.id(), paidTickets.getLast().id()), ownerToken,
                Map.of("requestKey", "after-is-too-late"));
        assertEquals(409, after.statusCode(), after.body());
        assertEquals(original.path("id").asLong(), request(paidTickets.getFirst(), "last-microsecond").path("id").asLong());
        finish(original, "SUCCEEDED");
        assertTicket(paidTickets.getFirst(), "REFUNDED");
        assertSeat(paidTickets.getFirst(), "AVAILABLE");
        assertTicket(paidTickets.getLast(), "VALID");
        assertSeat(paidTickets.getLast(), "SOLD");
        assertEquals(1, count("refund_record"));
    }

    @Test
    void anAcceptedRefundMayFailAfterAdmissionOpensButItsRestoredTicketCannotStartANewRefund() throws Exception {
        var open = sessions.get(sessionId).admissionOpenAt();
        clock.set(open.minusNanos(1_000));
        refreshTokens();
        var refund = request(paidTickets.getFirst(), "accepted-before-open");
        clock.set(open);
        finish(refund, "FAILED");
        assertTicket(paidTickets.getFirst(), "VALID");
        assertSeat(paidTickets.getFirst(), "SOLD");
        assertEquals("FAILED", request(paidTickets.getFirst(), "accepted-before-open").path("refundStatus").asString());
        var newRequest = refundHttp("POST", requestPath(order.id(), paidTickets.getFirst().id()), ownerToken,
                Map.of("requestKey", "new-after-open"));
        assertEquals(409, newRequest.statusCode(), newRequest.body());
        assertEquals(1, count("refund_record"));
    }

    @Test
    void requestWaitingForTheActualSessionLockRechecksTheCutoffAfterAcquiringIt() throws Exception {
        var open = sessions.get(sessionId).admissionOpenAt();
        clock.set(open.minusNanos(1_000));
        refreshTokens();
        var held = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var target = AopTestUtils.<CatalogPurchaseService>getUltimateTargetObject(purchase);
        doAnswer(call -> { entered.countDown(); return call.callRealMethod(); }).when(target).lockForPurchase(eq(sessionId));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var holder = pool.submit(() -> transaction().execute(status -> {
                jdbc.queryForObject("SELECT id FROM session WHERE id=? FOR UPDATE", Long.class, sessionId);
                held.countDown();
                await(release);
                return "RELEASED";
            }));
            assertTrue(held.await(10, TimeUnit.SECONDS));
            var requesting = pool.submit(() -> refundHttp("POST", requestPath(order.id(), paidTickets.getFirst().id()),
                    ownerToken, Map.of("requestKey", "waited-past-cutoff")));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                clock.set(open);
            } finally { release.countDown(); }
            assertEquals("RELEASED", holder.get(20, TimeUnit.SECONDS));
            var denied = requesting.get(20, TimeUnit.SECONDS);
            assertEquals(409, denied.statusCode(), denied.body());
        } finally { release.countDown(); }
        assertEquals(0, count("refund_record"));
        assertTicket(paidTickets.getFirst(), "VALID");
        assertSeat(paidTickets.getFirst(), "SOLD");
    }

    @Test
    void processingRemainsRefundingAndSoldAcrossRecoveryAndAnOldProcessingNoticeCannotUndoSuccess() throws Exception {
        var ticket = paidTickets.getFirst();
        var refund = request(ticket, "ordinary-processing");
        var processing = notice(refund, "PROCESSING");
        for (int seconds : List.of(1, 60, 601)) {
            clock.set(NOW.plusSeconds(seconds));
            var state = data(refundHttp("POST", "/api/operations/refunds/" + refund.path("id").asLong()
                    + "/recover", operatorToken, null));
            assertFalse(state.path("manualRequired").asBoolean());
            assertEquals(0, state.path("consecutiveFailures").asInt());
            assertTicket(ticket, "REFUNDING");
            assertSeat(ticket, "SOLD");
        }
        finish(refund, "SUCCEEDED");
        var beforeOldNotice = jdbc.queryForMap("SELECT * FROM ticket WHERE id=?", ticket.id());
        var seatBefore = jdbc.queryForMap("SELECT * FROM session_seat WHERE id=?", ticket.sessionSeatId());
        deliver(processing);
        assertEquals("SUCCEEDED", getRefund(refund).path("refundStatus").asString());
        assertEquals(beforeOldNotice, jdbc.queryForMap("SELECT * FROM ticket WHERE id=?", ticket.id()));
        assertEquals(seatBefore, jdbc.queryForMap("SELECT * FROM session_seat WHERE id=?", ticket.sessionSeatId()));
    }

    @Test
    void channelSuccessAppearingAfterANotificationBatchIsNormalPendingWorkAndRecoversOnTheNextPass()
            throws Exception {
        var ticket = paidTickets.getFirst();
        var refund = request(ticket, "success-between-batch-and-settlement");
        String number = refund.path("refundNumber").asString();
        long id = refund.path("id").asLong();
        var target = AopTestUtils.<RefundSimulatorService>getUltimateTargetObject(refundSimulator);
        var publish = new AtomicBoolean(true);
        doAnswer(call -> {
            var previousBatch = call.callRealMethod();
            // This independent channel commit occurs after the batch was read but before local settlement.
            // The current pass legitimately has not seen or applied its newly persisted success notification.
            if (publish.compareAndSet(true, false)) { target.succeed(number); }
            return previousBatch;
        }).when(target).notifications(eq(number));

        var pending = refunds.recover(id);
        assertFalse(publish.get(), "the test must publish success after the old notification batch was read");
        assertFalse(pending.manualRequired(), "a newly arrived original success notification is recoverable work");
        assertEquals(0, pending.consecutiveFailures());
        assertNotNull(pending.nextRecoveryAt());
        assertTicket(ticket, "REFUNDING");
        assertSeat(ticket, "SOLD");

        var completed = refunds.recover(id);
        assertEquals("SUCCEEDED", completed.refundStatus().name());
        assertFalse(completed.manualRequired());
        assertEquals(0, completed.consecutiveFailures());
        assertNull(completed.nextRecoveryAt());
        assertTicket(ticket, "REFUNDED");
        assertSeat(ticket, "AVAILABLE");
    }

    @Test
    void repeatedSuccessAndTheOriginalPaymentNeverUndoRefundsOrReleaseANewBuyersHeldOrSoldSeat() throws Exception {
        var originalTicket = paidTickets.getFirst();
        String oldCredential = credentials.credential(consumer1, order.id(), originalTicket.id()).credential();
        var refund = request(originalTicket, "return-to-sale");
        finish(refund, "SUCCEEDED");
        String success = notice(refund, "SUCCEEDED");
        int notificationCount = notifications(refund).size();
        terminal(refund, "SUCCEEDED");
        assertEquals(notificationCount, notifications(refund).size(), "terminal retries reuse their original snapshots");
        assertEquals(originalTicket.ticketNumber(), codec.verify(oldCredential));
        var oldQuery = refundHttp("GET", "/api/orders/" + order.id() + "/tickets/" + originalTicket.id()
                + "/credential", ownerToken, null);
        assertEquals(409, oldQuery.statusCode(), oldQuery.body());

        // A1 is the edge of this fixture's segment; buying this one returned seat creates no new orphan.
        var resale = orders.create(consumer2, "refund-resale-order",
                new CreateOrder(sessionId, MANUAL, List.of(originalTicket.sessionSeatId()))).order();
        var heldBefore = jdbc.queryForMap("SELECT * FROM session_seat WHERE id=?", originalTicket.sessionSeatId());
        assertEquals("HELD", heldBefore.get("seat_status"));
        deliver(success);
        payment.applyChannel(simulator.succeed(originalPayment.paymentNumber()));
        assertEquals(heldBefore, jdbc.queryForMap("SELECT * FROM session_seat WHERE id=?", originalTicket.sessionSeatId()));
        assertEquals("PARTIALLY_REFUNDED", orderRefunds().path("orderStatus").asString());

        var resalePayment = payment.start(consumer2, resale.id(), "refund-resale-payment");
        payment.applyChannel(simulator.succeed(resalePayment.paymentNumber()));
        var newTicket = payment.tickets(consumer2, resale.id()).getFirst();
        var soldBefore = jdbc.queryForMap("SELECT * FROM session_seat WHERE id=?", newTicket.sessionSeatId());
        var newTicketBefore = jdbc.queryForMap("SELECT * FROM ticket WHERE id=?", newTicket.id());
        deliver(success);
        payment.applyChannel(simulator.succeed(originalPayment.paymentNumber()));
        assertEquals(soldBefore, jdbc.queryForMap("SELECT * FROM session_seat WHERE id=?", newTicket.sessionSeatId()));
        assertEquals(newTicketBefore, jdbc.queryForMap("SELECT * FROM ticket WHERE id=?", newTicket.id()));
        assertTicket(originalTicket, "REFUNDED");
        assertTicket(newTicket, "VALID");
        assertSeat(newTicket, "SOLD");
        assertNotEquals(originalTicket.id(), newTicket.id());
        assertNotEquals(originalTicket.ticketNumber(), newTicket.ticketNumber());
        assertEquals(originalTicket.sessionSeatId(), newTicket.sessionSeatId());
        var newCredential = credentials.credential(consumer2, resale.id(), newTicket.id()).credential();
        assertEquals(originalTicket.ticketNumber(), codec.verify(oldCredential));
        assertEquals(newTicket.ticketNumber(), codec.verify(newCredential));
        assertEquals(3, count("ticket"));
        assertEquals("SUCCEEDED", payment.payments(consumer1, order.id()).getFirst().paymentStatus().name());
        assertEquals("PARTIALLY_REFUNDED", orderRefunds().path("orderStatus").asString());
    }
}
