package com.ticketing.trade;

import com.ticketing.simulator.PaymentSimulatorService;
import com.ticketing.support.Week2Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.ticketing.catalog.CatalogModels.SalesStatus.ON_SALE;
import static com.ticketing.catalog.CatalogRequests.UpdateSalesStatus;
import static com.ticketing.trade.TradeModels.OrderStatus.*;
import static com.ticketing.trade.TradeModels.SelectionMode.RECOMMENDED;
import static com.ticketing.trade.TradeRequests.CreateOrder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({PaymentTestSupport.TestClock.class, PaymentTestSupport.RealMapperSpies.class})
class PaymentRecoveryScannerIT extends PaymentTestSupport {
    @MockitoSpyBean private PaymentQueryService recoveryQueries;

    @Test
    void neverPaidOrderRemainsHeldBeforeTheDeadlineAndExpiresExactlyAtIt() {
        var scanner = scanner(50);
        clock.set(order.paymentExpiresAt().minusNanos(1_000));
        assertEquals(0, scanner.scanOnce());
        assertHeld(order);
        assertNull(payment.state(order.id()).cancellationRequestedAt());

        clock.set(order.paymentExpiresAt());
        assertEquals(1, scanner.scanOnce());
        assertCancelled(order, "EXPIRED");
        assertEquals("TIMEOUT", payment.state(order.id()).cancellationReason());
        assertEquals(0, scanner.scanOnce());
        assertEquals(0, count("payment_record"));
        assertEquals(0, count("simulated_payment"));
    }

    @Test
    void acceptedProcessingPaymentIsClosedBeforeTheExpiredSeatsAreReleased() {
        var attempt = payment.start(consumer1, order.id(), "scanner-close");
        clock.set(order.paymentExpiresAt());

        assertEquals(1, scanner(50).scanOnce());
        assertCancelled(order, "EXPIRED");
        assertEquals("CLOSED", simulator.find(attempt.paymentNumber()).orElseThrow().paymentStatus().name());
        var local = payment.payments(consumer1, order.id()).getFirst();
        assertEquals("CLOSED", local.paymentStatus().name());
        assertEquals(attempt.paymentNumber(), local.paymentNumber());
        assertEquals(order.paymentExpiresAt(), local.paymentExpiresAt());
        assertEquals(1, count("payment_record"));
        assertEquals(1, count("simulated_payment"));
    }

    @Test
    void undeliveredSuccessAndAnActualTicketWriteFailureAreRecoveredBeforeTheDeadline() throws Exception {
        var attempt = payment.start(consumer1, order.id(), "scanner-undelivered");
        simulator.succeed(attempt.paymentNumber());
        var success = simulator.notifications(attempt.paymentNumber()).stream()
                .filter(n -> n.observedStatus().name().equals("SUCCEEDED")).findFirst().orElseThrow();
        clock.set(payment.state(order.id()).nextRecoveryAt());
        assertTrue(clock.instant().isBefore(order.paymentExpiresAt()));
        failAfterFirstActualTicketInsert();

        var scanner = scanner(50);
        assertEquals(1, scanner.scanOnce());
        assertHeld(order);
        var failed = payment.state(order.id());
        assertEquals(1, failed.consecutiveFailures());
        assertEquals("LOCAL_APPLY_FAILED", failed.recentError());
        assertEquals("SUCCEEDED", simulator.find(attempt.paymentNumber()).orElseThrow().paymentStatus().name());
        assertEquals(0, scanner.scanOnce());
        clock.set(failed.nextRecoveryAt());
        assertTrue(clock.instant().isBefore(order.paymentExpiresAt()));
        assertEquals(1, scanner.scanOnce());
        assertPaid(order);
        assertEquals(0, payment.state(order.id()).consecutiveFailures());
        assertEquals(order.paymentExpiresAt(), orders.get(consumer1, order.id()).paymentExpiresAt());
        assertNull(simulator.notifications(attempt.paymentNumber()).stream()
                .filter(n -> n.notificationNumber().equals(success.notificationNumber())).findFirst().orElseThrow().deliveredAt(),
                "active querying applies the channel fact without acknowledging an undelivered notice");
        assertEquals(0, scanner.scanOnce());
        assertEquals(2, count("ticket"));
    }

    @Test
    void queryFailureAtExpiryKeepsInventoryAndDeadlineCannotBypassItsBackoff() {
        var attempt = payment.start(consumer1, order.id(), "scanner-query-failure");
        var calls = new AtomicInteger();
        doAnswer(call -> {
            if (calls.incrementAndGet() == 1) { throw new IllegalStateException("test channel query unavailable"); }
            return call.callRealMethod();
        }).when(channelTarget()).find(eq(attempt.paymentNumber()));
        clock.set(order.paymentExpiresAt());
        var scanner = scanner(50);

        assertEquals(1, scanner.scanOnce());
        assertHeld(order);
        var failed = payment.state(order.id());
        assertEquals(1, failed.consecutiveFailures());
        assertEquals("CHANNEL_UNAVAILABLE", failed.recentError());
        assertEquals(order.paymentExpiresAt(), failed.firstUnresolvedAt());
        assertEquals(order.paymentExpiresAt().plusSeconds(5), failed.nextRecoveryAt());
        assertNull(failed.recoveryClaimUntil());
        clock.set(failed.nextRecoveryAt().minusNanos(1_000));
        assertEquals(0, scanner.scanOnce());
        assertEquals(1, calls.get());
        assertHeld(order);

        clock.set(failed.nextRecoveryAt());
        assertEquals(1, scanner.scanOnce());
        assertEquals(2, calls.get());
        assertCancelled(order, "EXPIRED");
        assertEquals(1, count("payment_record"));
    }

    @Test
    void closeFailureIsNotProofOfNonPaymentAndRetriesOnlyAtItsPersistedTime() {
        var attempt = payment.start(consumer1, order.id(), "scanner-close-failure");
        var closes = new AtomicInteger();
        doAnswer(call -> {
            if (closes.incrementAndGet() == 1) { throw new IllegalStateException("test channel close unavailable"); }
            return call.callRealMethod();
        }).when(channelTarget()).close(any());
        clock.set(order.paymentExpiresAt());
        var scanner = scanner(50);

        assertEquals(1, scanner.scanOnce());
        assertHeld(order);
        assertEquals("PROCESSING", simulator.find(attempt.paymentNumber()).orElseThrow().paymentStatus().name());
        var failed = payment.state(order.id());
        assertEquals(1, failed.consecutiveFailures());
        assertEquals("CHANNEL_UNAVAILABLE", failed.recentError());
        assertEquals(0, scanner.scanOnce());
        assertEquals(1, closes.get());
        clock.set(failed.nextRecoveryAt());
        assertEquals(1, scanner.scanOnce());
        assertEquals(2, closes.get());
        assertCancelled(order, "EXPIRED");
    }

    @Test
    void sixthActualFailureStopsAutomaticScanningUntilOperatorRecovery() {
        var attempt = payment.start(consumer1, order.id(), "scanner-manual-threshold");
        var calls = new AtomicInteger();
        doAnswer(call -> {
            calls.incrementAndGet();
            throw new IllegalStateException("test persistent channel failure");
        }).when(channelTarget()).find(eq(attempt.paymentNumber()));
        clock.set(order.paymentExpiresAt());
        var scanner = scanner(50);

        for (int failures = 1; failures <= 6; failures++) {
            assertEquals(1, scanner.scanOnce());
            var state = payment.state(order.id());
            assertEquals(failures, state.consecutiveFailures());
            assertEquals(failures == 6, state.manualRequired());
            assertHeld(order);
            clock.set(state.nextRecoveryAt());
        }
        assertEquals(0, scanner.scanOnce());
        assertEquals(6, calls.get());
        assertTrue(payment.state(order.id()).manualRequired());

        doAnswer(call -> call.callRealMethod()).when(channelTarget()).find(eq(attempt.paymentNumber()));
        payment.recover(order.id());
        assertCancelled(order, "EXPIRED");
        assertFalse(payment.state(order.id()).manualRequired());
        assertEquals(0, payment.state(order.id()).consecutiveFailures());
    }

    @Test
    void liveLeaseIsSkippedAndExpiredLeaseInterruptionIsCountedOnlyOnce() {
        var attempt = payment.start(consumer1, order.id(), "scanner-expired-lease");
        clock.set(order.paymentExpiresAt());
        var until = clock.instant().plusSeconds(60);
        jdbc.update("UPDATE customer_order SET recovery_token=?,recovery_claim_until=? WHERE id=?",
                "test-interrupted-worker", Timestamp.from(until), order.id());
        var scanner = scanner(50);
        assertEquals(0, scanner.scanOnce());
        assertHeld(order);
        assertNull(payment.state(order.id()).cancellationRequestedAt());

        doAnswer(call -> { throw new IllegalStateException("test replacement worker query failure"); })
                .when(channelTarget()).find(eq(attempt.paymentNumber()));
        clock.set(until);
        assertEquals(1, scanner.scanOnce());
        var state = payment.state(order.id());
        assertEquals(2, state.consecutiveFailures(), "one expired worker plus one actual replacement failure");
        assertEquals(until, state.firstUnresolvedAt());
        assertNull(state.recoveryClaimUntil());
        assertEquals(until.plusSeconds(15), state.nextRecoveryAt());
        assertEquals(0, scanner.scanOnce());
        assertEquals(2, payment.state(order.id()).consecutiveFailures());
        assertHeld(order);
    }

    @Test
    void manualFlagOrBackoffAppearingAfterSelectionIsRecheckedBeforeAnyCancellationWrite() {
        var backoffOrder = newOrder("scanner-backoff-race", "FLOOR-A", "B");
        clock.set(order.paymentExpiresAt());
        var selects = new AtomicInteger();
        doAnswer(call -> {
            var candidates = call.callRealMethod();
            if (selects.incrementAndGet() == 1) {
                writer.markConflict(order.id());
                writer.noteResultFailure(backoffOrder.id(), "CHANNEL_UNAVAILABLE");
            }
            return candidates;
        }).when(queryTarget()).dueRecoveryOrders(any(Instant.class), anyLong(), anyLong(), anyInt());

        assertEquals(2, scanner(50).scanOnce(), "selected count does not imply a claim was granted");
        assertHeld(order);
        assertHeld(backoffOrder);
        assertTrue(payment.state(order.id()).manualRequired());
        assertNull(payment.state(order.id()).cancellationRequestedAt());
        var backoff = payment.state(backoffOrder.id());
        assertEquals(clock.instant().plusSeconds(5), backoff.nextRecoveryAt());
        assertNull(backoff.cancellationRequestedAt());
        assertEquals(0, count("payment_record"));
    }

    @Test
    void repeatScanningOfAnOldExpiredOrderCannotReleaseItsSeatsAfterAnotherOrderOwnsThem() {
        clock.set(order.paymentExpiresAt());
        var scanner = scanner(50);
        assertEquals(1, scanner.scanOnce());
        assertCancelled(order, "EXPIRED");
        var replacement = newOrder("scanner-new-holder", "FLOOR-A", "A");

        assertEquals(0, scanner.scanOnce());
        assertEquals(CANCELLED, payment.recoverIfDue(order.id()).orderStatus());
        assertEquals(0, scanner.scanOnce());
        assertHeld(replacement);
        assertEquals(CANCELLED, orders.get(consumer1, order.id()).orderStatus());
        assertEquals("EXPIRED", orders.get(consumer1, order.id()).hold().status().name());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM session_seat WHERE current_hold_id=? AND seat_status='HELD'",
                Integer.class, replacement.hold().id()));
    }

    @Test
    void twoScannersSelectingTheSameCandidateCannotDuplicateItsLeaseOrFailureCount() throws Exception {
        var attempt = payment.start(consumer1, order.id(), "scanner-same-candidate");
        clock.set(order.paymentExpiresAt());
        var selectedTogether = new CyclicBarrier(2);
        var selections = new AtomicInteger();
        doAnswer(call -> {
            var candidates = call.callRealMethod();
            if (selections.incrementAndGet() <= 2) { selectedTogether.await(10, TimeUnit.SECONDS); }
            return candidates;
        }).when(queryTarget()).dueRecoveryOrders(any(Instant.class), anyLong(), anyLong(), anyInt());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var channelQueries = new AtomicInteger();
        doAnswer(call -> {
            channelQueries.incrementAndGet();
            entered.countDown();
            await(release);
            throw new IllegalStateException("test single claimed channel failure");
        }).when(channelTarget()).find(eq(attempt.paymentNumber()));

        try (var pool = Executors.newFixedThreadPool(2)) {
            var completed = new ExecutorCompletionService<Integer>(pool);
            completed.submit(() -> scanner(50).scanOnce());
            completed.submit(() -> scanner(50).scanOnce());
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                var skipped = completed.poll(10, TimeUnit.SECONDS);
                assertNotNull(skipped, "the other scanner must skip the active claim without waiting for the channel");
                assertEquals(1, skipped.get());
                assertEquals(1, channelQueries.get());
                assertEquals(0, payment.state(order.id()).consecutiveFailures());
                assertEquals(clock.instant().plusSeconds(60), payment.state(order.id()).recoveryClaimUntil());
            } finally { release.countDown(); }
            var settled = completed.poll(10, TimeUnit.SECONDS);
            assertNotNull(settled);
            assertEquals(1, settled.get());
        } finally { release.countDown(); }
        assertHeld(order);
        assertEquals(1, channelQueries.get());
        assertEquals(1, payment.state(order.id()).consecutiveFailures());
        assertNull(payment.state(order.id()).recoveryClaimUntil());
    }

    @Test
    void timeoutScanningRacingChannelSuccessOrDuplicateNoticeKeepsTheFinalInventoryConsistent() throws Exception {
        var attempt = payment.start(consumer1, order.id(), "scanner-success-race");
        var noticeOrder = newOrder("scanner-notice-order", "FLOOR-A", "B");
        var noticeAttempt = payment.start(consumer1, noticeOrder.id(), "scanner-notice-race");
        simulator.succeed(noticeAttempt.paymentNumber());
        var notice = simulator.notifications(noticeAttempt.paymentNumber()).stream()
                .filter(n -> n.observedStatus().name().equals("SUCCEEDED")).findFirst().orElseThrow();
        clock.set(order.paymentExpiresAt());

        compete(() -> { scanner(1).scanOnce(); return "SCANNED"; },
                () -> paymentOutcome(() -> payment.applyChannel(simulator.succeed(attempt.paymentNumber()))));
        var status = orders.get(consumer1, order.id()).orderStatus();
        if (status == PAID) { assertPaid(order); }
        else { assertEquals(CANCELLED, status); assertCancelled(order, "EXPIRED"); }
        assertEquals(status == PAID ? "SUCCEEDED" : "CLOSED",
                payment.payments(consumer1, order.id()).getFirst().paymentStatus().name());

        compete(() -> { scanner(50).scanOnce(); return "SCANNED"; },
                () -> { payment.deliver(notice.notificationNumber()); return "DELIVERED"; });
        assertPaid(noticeOrder);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ticket WHERE order_id=?", Integer.class, noticeOrder.id()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE order_id=?", Integer.class, noticeOrder.id()));
        assertNotNull(simulator.notifications(noticeAttempt.paymentNumber()).stream()
                .filter(n -> n.notificationNumber().equals(notice.notificationNumber())).findFirst().orElseThrow().deliveredAt());
    }

    @Test
    void oneCandidateThrowingDoesNotStopLaterOrdersAndItIsRediscoveredOnTheNextTraversal() {
        var other = newOrder("scanner-after-fault", "FLOOR-A", "B");
        clock.set(order.paymentExpiresAt());
        var claims = new AtomicInteger();
        doAnswer(call -> {
            if (claims.incrementAndGet() == 1) { throw new IllegalStateException("test claim database unavailable"); }
            return call.callRealMethod();
        }).when(writerTarget()).claim(eq(order.id()), eq(false));
        var scanner = scanner(50);

        assertEquals(2, scanner.scanOnce());
        assertHeld(order);
        assertCancelled(other, "EXPIRED");
        assertEquals(1, scanner.scanOnce());
        assertCancelled(order, "EXPIRED");
        assertEquals(2, claims.get());
    }

    @Test
    void batchesStayBoundedAndVisitEveryDueOrderAcrossMultipleCalls() {
        var second = newOrder("scanner-batch-two", "FLOOR-A", "B");
        var third = newOrder("scanner-batch-three", "BALCONY-B", "D");
        var fourth = newOrder("scanner-batch-four", "BALCONY-B", "E");
        var fifth = independentOrder("BATCH-FIVE");
        clock.set(order.paymentExpiresAt());
        var scanner = scanner(2);

        assertEquals(2, scanner.scanOnce());
        assertCancelled(order, "EXPIRED");
        assertCancelled(second, "EXPIRED");
        assertHeld(third);
        assertHeld(fourth);
        assertHeld(fifth);
        assertEquals(2, scanner.scanOnce());
        assertCancelled(third, "EXPIRED");
        assertCancelled(fourth, "EXPIRED");
        assertHeld(fifth);
        assertEquals(1, scanner.scanOnce());
        assertCancelled(fifth, "EXPIRED");
        assertEquals(0, scanner.scanOnce());
    }

    @Test
    void aFixedTraversalUpperBoundRevisitsLowIdsEvenWhileNewDueHighIdsKeepArriving() {
        var attempt = payment.start(consumer1, order.id(), "scanner-low-id-fault");
        var second = newOrder("scanner-traversal-two", "FLOOR-A", "B");
        var third = newOrder("scanner-traversal-three", "BALCONY-B", "D");
        doAnswer(call -> { throw new IllegalStateException("test persistent low ID channel fault"); })
                .when(channelTarget()).find(eq(attempt.paymentNumber()));
        clock.set(order.paymentExpiresAt());
        var scanner = scanner(1);
        assertEquals(1, scanner.scanOnce());
        assertEquals(1, payment.state(order.id()).consecutiveFailures());
        clock.set(payment.state(order.id()).nextRecoveryAt());

        var firstArrival = arrivingDueOrder("ARRIVAL-ONE");
        assertEquals(1, scanner.scanOnce());
        assertCancelled(second, "EXPIRED");
        var secondArrival = arrivingDueOrder("ARRIVAL-TWO");
        assertEquals(1, scanner.scanOnce());
        assertCancelled(third, "EXPIRED");
        var thirdArrival = arrivingDueOrder("ARRIVAL-THREE");

        assertEquals(1, scanner.scanOnce(), "the next traversal must return to the due low ID first");
        assertEquals(2, payment.state(order.id()).consecutiveFailures());
        assertHeld(order);
        assertHeld(firstArrival);
        assertHeld(secondArrival);
        assertHeld(thirdArrival);
        assertEquals(1, scanner.scanOnce());
        assertCancelled(firstArrival, "EXPIRED");
        assertEquals(1, scanner.scanOnce());
        assertCancelled(secondArrival, "EXPIRED");
        assertEquals(1, scanner.scanOnce());
        assertCancelled(thirdArrival, "EXPIRED");
    }

    private PaymentRecoveryScanner scanner(int batchSize) {
        return new PaymentRecoveryScanner(recoveryQueries, payment, clock, batchSize);
    }

    private PaymentSimulatorService channelTarget() { return AopTestUtils.getUltimateTargetObject(simulator); }
    private PaymentQueryService queryTarget() { return AopTestUtils.getUltimateTargetObject(recoveryQueries); }
    private PaymentTransactionService writerTarget() { return AopTestUtils.getUltimateTargetObject(writer); }

    private TradeModels.OrderDetails independentOrder(String code) {
        var independentSession = sessions.create(Week2Fixtures.request("SESSION-SCAN-" + code, fixture)).id();
        management.setSalesStatus(independentSession, new UpdateSalesStatus(ON_SALE));
        var selected = sessions.listSeats(independentSession).stream()
                .filter(s -> s.areaCodeSnapshot().equals("FLOOR-A") && s.rowLabelSnapshot().equals("A")
                        && s.seatNumberSnapshot() <= 2).map(s -> s.id()).toList();
        return orders.create(consumer1, "scanner-" + code, new CreateOrder(independentSession, RECOMMENDED, selected)).order();
    }

    private TradeModels.OrderDetails arrivingDueOrder(String code) {
        var scanTime = clock.instant();
        // Newly inserted IDs share the original deadline, isolating traversal fairness from real elapsed time.
        clock.set(NOW);
        try { return independentOrder(code); }
        finally { clock.set(scanTime); }
    }
}
