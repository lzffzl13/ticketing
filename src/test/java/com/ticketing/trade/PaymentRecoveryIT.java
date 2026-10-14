package com.ticketing.trade;

import com.ticketing.simulator.PaymentSimulatorService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.ticketing.trade.TradeModels.OrderStatus.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({PaymentTestSupport.TestClock.class, PaymentTestSupport.RealMapperSpies.class})
class PaymentRecoveryIT extends PaymentTestSupport {
    @Test
    void cancelWithoutAnyPaymentDirectlyReleasesTheWholeGroupAndIsRepeatable() {
        var cancelled = payment.cancel(consumer1, order.id());
        assertEquals(CANCELLED, cancelled.orderStatus());
        assertNotNull(cancelled.cancellationRequestedAt());
        assertEquals("USER", cancelled.cancellationReason().toString());
        assertCancelled(order, "RELEASED");
        assertEquals(CANCELLED, payment.cancel(consumer1, order.id()).orderStatus());
        assertEquals(0, count("payment_record"));
        assertEquals(0, count("simulated_payment"));
        assertStatus(HttpStatus.CONFLICT, () -> payment.start(consumer1, order.id(), "after-cancel"));
    }

    @Test
    void explicitRecoveryOfAnExpiredOrderWithoutPaymentsSafelyCancelsWithAnExpiredHold() {
        clock.set(order.paymentExpiresAt());
        var result = payment.recover(order.id());
        assertEquals(CANCELLED, result.orderStatus());
        assertEquals("TIMEOUT", result.cancellationReason().toString());
        assertCancelled(order, "EXPIRED");
        assertEquals(CANCELLED, payment.recover(order.id()).orderStatus());
        assertEquals(0, count("payment_record"));
    }

    @Test
    void automaticRecoveryCannotReleaseAnExpiredFailedPaymentMarkedForManualReview() {
        var attempt = payment.start(consumer1, order.id(), "manual-review-failed");
        payment.applyChannel(simulator.fail(attempt.paymentNumber()));
        clock.set(order.paymentExpiresAt());
        // This is the persisted marker a result handler can set after a scanner has read its candidate.
        writer.markConflict(order.id());
        var before = payment.state(order.id());
        var storedBefore = jdbc.queryForMap("SELECT * FROM customer_order WHERE id=?", order.id());
        assertTrue(before.manualRequired());
        assertEquals("FAILED", before.payments().getFirst().paymentStatus().name());

        assertEquals(before, payment.recoverIfDue(order.id()));

        assertEquals(storedBefore, jdbc.queryForMap("SELECT * FROM customer_order WHERE id=?", order.id()));
        assertNull(payment.state(order.id()).cancellationRequestedAt());
        assertHeld(order);
    }

    @Test
    void automaticRecoveryCannotBypassBackoffToReleaseAnExpiredFailedPayment() {
        var attempt = payment.start(consumer1, order.id(), "backoff-failed");
        payment.applyChannel(simulator.fail(attempt.paymentNumber()));
        clock.set(order.paymentExpiresAt().minusSeconds(1));
        // An in-flight recovery query fails just as the deadline arrives, persisting a real future retry.
        doAnswer(call -> {
            clock.set(order.paymentExpiresAt());
            throw new IllegalStateException("test channel query fails across the payment deadline");
        }).when(channelTarget()).find(eq(attempt.paymentNumber()));
        var before = payment.recover(order.id());
        var storedBefore = jdbc.queryForMap("SELECT * FROM customer_order WHERE id=?", order.id());
        assertEquals(order.paymentExpiresAt().plusSeconds(5), before.nextRecoveryAt());
        assertEquals(1, before.consecutiveFailures());
        assertEquals("FAILED", before.payments().getFirst().paymentStatus().name());

        assertEquals(before, payment.recoverIfDue(order.id()));

        assertEquals(storedBefore, jdbc.queryForMap("SELECT * FROM customer_order WHERE id=?", order.id()));
        assertNull(payment.state(order.id()).cancellationRequestedAt());
        assertHeld(order);
        restoreFind();
        clock.set(before.nextRecoveryAt());
        assertEquals(CANCELLED, payment.recoverIfDue(order.id()).orderStatus());
        assertCancelled(order, "EXPIRED");
    }

    @Test
    void automaticRecoveryCannotReleaseAnExpiredFailedPaymentDuringAnotherWorkersLiveLease() {
        var attempt = payment.start(consumer1, order.id(), "leased-failed");
        payment.applyChannel(simulator.fail(attempt.paymentNumber()));
        clock.set(order.paymentExpiresAt().minusSeconds(1));
        // The existing worker has committed its claim and has not completed its channel interaction yet.
        var work = writer.claim(order.id(), true);
        assertNotNull(work);
        clock.set(order.paymentExpiresAt());
        var before = payment.state(order.id());
        var storedBefore = jdbc.queryForMap("SELECT * FROM customer_order WHERE id=?", order.id());
        assertEquals(order.paymentExpiresAt().plusSeconds(59), before.recoveryClaimUntil());
        assertEquals("FAILED", before.payments().getFirst().paymentStatus().name());

        assertEquals(before, payment.recoverIfDue(order.id()));

        assertEquals(storedBefore, jdbc.queryForMap("SELECT * FROM customer_order WHERE id=?", order.id()));
        assertNull(payment.state(order.id()).cancellationRequestedAt());
        assertHeld(order);
    }

    @Test
    void automaticRecoveryDoesNothingForAnUnexpiredOrderWithoutRecoveryTimeOrCancellationIntent() {
        var before = payment.state(order.id());
        var storedBefore = jdbc.queryForMap("SELECT * FROM customer_order WHERE id=?", order.id());
        assertNull(before.nextRecoveryAt());
        assertNull(before.cancellationRequestedAt());

        assertEquals(before, payment.recoverIfDue(order.id()));

        assertEquals(storedBefore, jdbc.queryForMap("SELECT * FROM customer_order WHERE id=?", order.id()));
        assertHeld(order);
        assertEquals(0, count("payment_record"));
        assertEquals(0, count("simulated_payment"));
        // An operator may still explicitly inspect this same order before any automatic work is due.
        var manual = payment.recover(order.id());
        assertEquals(PENDING_PAYMENT, manual.orderStatus());
        assertEquals(order.paymentExpiresAt(), manual.nextRecoveryAt());
        assertHeld(order);
    }

    @Test
    void abandonedSixthAttemptRequiresManualReviewBeforeAutomaticallyReleasingFailedPayments() {
        var attempt = payment.start(consumer1, order.id(), "abandoned-sixth");
        failFind();
        for (int failure = 1; failure <= 5; failure++) {
            assertEquals(failure, payment.recover(order.id()).consecutiveFailures());
        }
        restoreFind();
        // A worker applies the terminal channel fact, then crashes before settling its existing claim.
        var abandoned = writer.claim(order.id(), true);
        assertNotNull(abandoned);
        payment.applyChannel(simulator.fail(attempt.paymentNumber()));
        clock.set(order.paymentExpiresAt());

        var paused = payment.recoverIfDue(order.id());

        assertTrue(paused.manualRequired());
        assertEquals(6, paused.consecutiveFailures());
        assertEquals("RECOVERY_LEASE_EXPIRED", paused.recentError());
        assertNull(paused.recoveryClaimUntil());
        assertEquals("FAILED", paused.payments().getFirst().paymentStatus().name());
        assertHeld(order);
        assertEquals(CANCELLED, payment.recover(order.id()).orderStatus());
        assertCancelled(order, "EXPIRED");
    }

    @Test
    void processingPaymentIsClosedBeforeUserCancellationAndOldKeysNeverReopenIt() {
        var attempt = payment.start(consumer1, order.id(), "processing");
        var cancelled = payment.cancel(consumer1, order.id());
        assertEquals(CANCELLED, cancelled.orderStatus());
        assertCancelled(order, "RELEASED");
        assertEquals("CLOSED", simulator.find(attempt.paymentNumber()).orElseThrow().paymentStatus().name());
        assertEquals("CLOSED", payment.start(consumer1, order.id(), "processing").paymentStatus().name());
        assertStatus(HttpStatus.CONFLICT, () -> payment.start(consumer1, order.id(), "replacement"));
        assertEquals(1, count("payment_record"));
        assertEquals(CANCELLED, payment.cancel(consumer1, order.id()).orderStatus());
    }

    @Test
    void previouslyFailedPaymentCanBeSafelyCancelledWithoutRewritingItsTerminalAsClosed() {
        var attempt = payment.start(consumer1, order.id(), "failed");
        payment.applyChannel(simulator.fail(attempt.paymentNumber()));
        payment.cancel(consumer1, order.id());
        assertCancelled(order, "RELEASED");
        assertEquals("FAILED", simulator.find(attempt.paymentNumber()).orElseThrow().paymentStatus().name());
        assertEquals("FAILED", payment.payments(consumer1, order.id()).getFirst().paymentStatus().name());
    }

    @Test
    void successfulChannelFactOverridesACancellationRequestAndCompletesTickets() {
        var attempt = payment.start(consumer1, order.id(), "already-paid");
        simulator.succeed(attempt.paymentNumber());
        var result = payment.cancel(consumer1, order.id());
        assertEquals(PAID, result.orderStatus());
        assertPaid(order);
        assertEquals(PAID, payment.cancel(consumer1, order.id()).orderStatus());
    }

    @Test
    void queryFailureKeepsSeatsAndCancellationIntentBlocksNewPaymentUntilSameNumberRecovers() {
        var attempt = payment.start(consumer1, order.id(), "query-failure");
        failFind();
        var unresolved = payment.cancel(consumer1, order.id());
        assertHeld(order);
        assertNotNull(unresolved.cancellationRequestedAt());
        assertEquals(1, unresolved.consecutiveFailures());
        assertEquals(NOW, unresolved.firstUnresolvedAt());
        assertEquals(NOW.plusSeconds(5), unresolved.nextRecoveryAt());
        assertFalse(unresolved.manualRequired());
        assertStatus(HttpStatus.CONFLICT, () -> payment.start(consumer1, order.id(), "new-during-cancel"));
        restoreFind();
        var restored = payment.recover(order.id());
        assertCancelled(order, "RELEASED");
        assertResolved(restored);
        assertEquals("CLOSED", simulator.find(attempt.paymentNumber()).orElseThrow().paymentStatus().name());
        assertEquals(1, count("payment_record"));
    }

    @Test
    void closeFailureIsNotProofOfNonPaymentAndRecoveryDoesNotCreateAnotherAttempt() {
        var attempt = payment.start(consumer1, order.id(), "close-failure");
        var target = channelTarget();
        var calls = new AtomicInteger();
        doAnswer(call -> {
            if (calls.incrementAndGet() == 1) { throw new IllegalStateException("test channel close unavailable"); }
            return call.callRealMethod();
        }).when(target).close(any());
        var unresolved = payment.cancel(consumer1, order.id());
        assertEquals(1, unresolved.consecutiveFailures());
        assertHeld(order);
        assertEquals("PROCESSING", simulator.find(attempt.paymentNumber()).orElseThrow().paymentStatus().name());
        var restored = payment.recover(order.id());
        assertCancelled(order, "RELEASED");
        assertResolved(restored);
        assertEquals(1, count("payment_record"));
    }

    @Test
    void actualPartialReleaseFailureRollsBackLocalCancellationButKeepsTheIndependentClosedChannel() throws Exception {
        var attempt = payment.start(consumer1, order.id(), "release-fault");
        failAfterFirstActualInventoryRelease();
        var failed = payment.cancel(consumer1, order.id());
        assertEquals(PENDING_PAYMENT, failed.orderStatus());
        assertEquals(1, failed.consecutiveFailures());
        assertHeld(order);
        assertEquals("PROCESSING", payment.payments(consumer1, order.id()).getFirst().paymentStatus().name());
        assertEquals("CLOSED", simulator.find(attempt.paymentNumber()).orElseThrow().paymentStatus().name());
        assertEquals(CANCELLED, payment.recover(order.id()).orderStatus());
        assertCancelled(order, "RELEASED");
        assertEquals("CLOSED", payment.payments(consumer1, order.id()).getFirst().paymentStatus().name());
    }

    @Test
    void cancellationIntentStillBlocksNewPaymentWhenThePreviousPaymentFailsDuringTheChannelQuery() throws Exception {
        var attempt = payment.start(consumer1, order.id(), "cancel-race");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            var oldObservation = call.callRealMethod();
            entered.countDown();
            await(release);
            return oldObservation;
        }).when(channelTarget()).find(eq(attempt.paymentNumber()));
        try (var pool = Executors.newSingleThreadExecutor()) {
            var cancelling = pool.submit(() -> payment.cancel(consumer1, order.id()));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                payment.applyChannel(simulator.fail(attempt.paymentNumber()));
                assertStatus(HttpStatus.CONFLICT, () -> payment.start(consumer1, order.id(), "attempt-during-cancel"));
                assertEquals(1, count("payment_record"));
            } finally { release.countDown(); }
            assertEquals(CANCELLED, cancelling.get(20, TimeUnit.SECONDS).orderStatus());
        } finally { release.countDown(); }
        assertCancelled(order, "RELEASED");
    }

    @Test
    void startFailurePersistsInitiatedAndRecoveryResubmitsTheOriginalNumberBeforeExpiry() {
        var target = channelTarget();
        var calls = new AtomicInteger();
        doAnswer(call -> {
            if (calls.incrementAndGet() == 1) { throw new IllegalStateException("test interruption before channel acceptance"); }
            return call.callRealMethod();
        }).when(target).start(any());
        var attempt = payment.start(consumer1, order.id(), "unsubmitted");
        assertEquals("INITIATED", attempt.paymentStatus().name());
        assertTrue(simulator.find(attempt.paymentNumber()).isEmpty());
        assertHeld(order);
        var restored = payment.recover(order.id());
        assertEquals(PENDING_PAYMENT, restored.orderStatus());
        assertResolved(restored);
        assertEquals("PROCESSING", restored.payments().getFirst().paymentStatus().name());
        assertEquals(attempt.paymentNumber(), restored.payments().getFirst().paymentNumber());
        assertEquals(1, count("payment_record"));
        assertEquals(1, count("simulated_payment"));
    }

    @Test
    void initiatedWithoutAChannelTransactionIsClosedAsAMissingNumberAfterExpiry() {
        var target = channelTarget();
        doAnswer(call -> { throw new IllegalStateException("test unsubmitted channel request"); }).when(target).start(any());
        var attempt = payment.start(consumer1, order.id(), "missing-after-expiry");
        assertEquals("INITIATED", attempt.paymentStatus().name());
        clock.set(order.paymentExpiresAt());
        var recovered = payment.recover(order.id());
        assertEquals(CANCELLED, recovered.orderStatus());
        assertCancelled(order, "EXPIRED");
        var tombstone = simulator.find(attempt.paymentNumber()).orElseThrow();
        assertEquals("CLOSED", tombstone.paymentStatus().name());
        assertNull(tombstone.acceptedAt());
        assertEquals(1, count("payment_record"));
    }

    @Test
    void sixConsecutiveActualFailuresUseTheApprovedBackoffAndThenRequireManualIntervention() {
        payment.start(consumer1, order.id(), "threshold");
        failFind();
        long[] seconds = {5, 15, 30, 60, 120};
        for (int failure = 1; failure <= 6; failure++) {
            var at = NOW.plusSeconds(failure - 1);
            clock.set(at);
            var state = payment.recover(order.id());
            assertEquals(failure, state.consecutiveFailures());
            assertEquals(NOW, state.firstUnresolvedAt());
            assertEquals(failure == 6, state.manualRequired());
            assertNotNull(state.recentError());
            if (failure < 6) { assertEquals(at.plusSeconds(seconds[failure - 1]), state.nextRecoveryAt()); }
            assertHeld(order);
        }
        restoreFind();
        var restored = payment.recover(order.id());
        assertEquals(PENDING_PAYMENT, restored.orderStatus());
        assertResolved(restored);
        assertHeld(order);
    }

    @Test
    void sixTicketWriteFailuresOnTheSameSuccessNoticeRequireManualInterventionWithoutAcknowledgingIt() throws Exception {
        var attempt = payment.start(consumer1, order.id(), "notice-ticket-threshold");
        simulator.succeed(attempt.paymentNumber());
        var success = simulator.notifications(attempt.paymentNumber()).stream()
                .filter(n -> n.observedStatus().name().equals("SUCCEEDED")).findFirst().orElseThrow();
        failAfterEveryActualTicketInsert();
        long[] seconds = {5, 15, 30, 60, 120};
        for (int failure = 1; failure <= 6; failure++) {
            var at = NOW.plusSeconds(failure - 1);
            clock.set(at);
            assertThrows(IllegalStateException.class, () -> payment.deliver(success.notificationNumber()));
            var unresolved = payment.state(order.id());
            assertEquals(failure, unresolved.consecutiveFailures());
            assertEquals(NOW, unresolved.firstUnresolvedAt());
            assertEquals("LOCAL_APPLY_FAILED", unresolved.recentError());
            assertEquals(failure == 6, unresolved.manualRequired());
            assertNull(unresolved.recoveryClaimUntil());
            if (failure < 6) { assertEquals(at.plusSeconds(seconds[failure - 1]), unresolved.nextRecoveryAt()); }
            assertEquals("PROCESSING", unresolved.payments().getFirst().paymentStatus().name());
            assertHeld(order);
            assertNull(simulator.notifications(attempt.paymentNumber()).stream()
                    .filter(n -> n.notificationNumber().equals(success.notificationNumber())).findFirst().orElseThrow().deliveredAt());
        }
        assertEquals("SUCCEEDED", simulator.find(attempt.paymentNumber()).orElseThrow().paymentStatus().name());
        assertEquals(1, count("payment_record"));
        assertEquals(0, count("ticket"));
    }

    @Test
    void anOldFailedNoticeCannotClearTheNewInitiatedPaymentsSubmissionFailure() {
        var first = payment.start(consumer1, order.id(), "old-failed-notice");
        simulator.fail(first.paymentNumber());
        var failedNotice = simulator.notifications(first.paymentNumber()).stream()
                .filter(n -> n.observedStatus().name().equals("FAILED")).findFirst().orElseThrow();
        payment.deliver(failedNotice.notificationNumber());

        clock.set(NOW.plusSeconds(7));
        doAnswer(call -> { throw new IllegalStateException("test new channel submission unavailable"); })
                .when(channelTarget()).start(any());
        var second = payment.start(consumer1, order.id(), "new-initiated-failure");
        assertEquals("INITIATED", second.paymentStatus().name());
        assertTrue(simulator.find(second.paymentNumber()).isEmpty());
        var failed = payment.state(order.id());
        assertEquals(1, failed.consecutiveFailures());
        assertEquals(NOW.plusSeconds(7), failed.firstUnresolvedAt());
        assertEquals("CHANNEL_UNAVAILABLE", failed.recentError());

        clock.set(NOW.plusSeconds(8));
        payment.deliver(failedNotice.notificationNumber());
        var afterOldNotice = payment.state(order.id());
        assertEquals(failed.consecutiveFailures(), afterOldNotice.consecutiveFailures());
        assertEquals(failed.firstUnresolvedAt(), afterOldNotice.firstUnresolvedAt());
        assertEquals(failed.recentError(), afterOldNotice.recentError());
        assertFalse(afterOldNotice.manualRequired());
        assertNull(afterOldNotice.recoveryClaimUntil());
        assertEquals(List.of("FAILED", "INITIATED"), afterOldNotice.payments().stream()
                .map(p -> p.paymentStatus().name()).toList());
        assertHeld(order);
        assertEquals(2, count("payment_record"));
        assertEquals(1, count("simulated_payment"));
    }

    @Test
    void creatingANewAttemptInvalidatesTheOldFailedPaymentsWorkerBeforeItsLateFailure() throws Exception {
        var first = payment.start(consumer1, order.id(), "old-attempt-worker");
        payment.applyChannel(simulator.fail(first.paymentNumber()));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            await(release);
            throw new IllegalStateException("test late failure of old failed payment worker");
        }).when(channelTarget()).find(eq(first.paymentNumber()));
        doAnswer(call -> { throw new IllegalStateException("test new attempt submission failure"); })
                .when(channelTarget()).start(any());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var oldWorker = pool.submit(() -> payment.recover(order.id()));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertEquals(NOW.plusSeconds(60), payment.state(order.id()).recoveryClaimUntil());
                clock.set(NOW.plusSeconds(1));
                var second = payment.start(consumer1, order.id(), "new-attempt-worker");
                assertNotEquals(first.paymentNumber(), second.paymentNumber());
                assertEquals("INITIATED", second.paymentStatus().name());
                var newFailure = payment.state(order.id());
                assertEquals(1, newFailure.consecutiveFailures());
                assertEquals(NOW.plusSeconds(1), newFailure.firstUnresolvedAt());
                assertEquals("CHANNEL_UNAVAILABLE", newFailure.recentError());
                assertNull(newFailure.recoveryClaimUntil());
            } finally { release.countDown(); }
            var afterOldFailure = oldWorker.get(20, TimeUnit.SECONDS);
            assertEquals(1, afterOldFailure.consecutiveFailures(), "the old worker cannot count a failure for the new attempt");
            assertEquals(NOW.plusSeconds(1), afterOldFailure.firstUnresolvedAt());
            assertEquals("CHANNEL_UNAVAILABLE", afterOldFailure.recentError());
            assertEquals(NOW.plusSeconds(6), afterOldFailure.nextRecoveryAt());
            assertNull(afterOldFailure.recoveryClaimUntil());
            assertFalse(afterOldFailure.manualRequired());
            assertEquals(List.of("FAILED", "INITIATED"), afterOldFailure.payments().stream()
                    .map(p -> p.paymentStatus().name()).toList());
        } finally { release.countDown(); }
        assertHeld(order);
        assertEquals(2, count("payment_record"));
        assertEquals(1, count("simulated_payment"));
    }

    @Test
    void tenMinutesOfActualFailureCanRequireInterventionBeforeSixAttempts() {
        payment.start(consumer1, order.id(), "time-threshold");
        failFind();
        assertFalse(payment.recover(order.id()).manualRequired());
        clock.set(NOW.plusSeconds(601));
        var unresolved = payment.recover(order.id());
        assertEquals(2, unresolved.consecutiveFailures());
        assertTrue(unresolved.manualRequired());
        assertEquals(NOW, unresolved.firstUnresolvedAt());
        assertHeld(order);
    }

    @Test
    void normalProcessingIsNotAFailureAndSuccessfulQueryClearsAPreviousTemporaryFault() {
        payment.start(consumer1, order.id(), "ordinary-processing");
        for (var after : List.of(1L, 60L, 601L)) {
            clock.set(NOW.plusSeconds(after));
            assertResolved(payment.recover(order.id()));
        }
        failFind();
        assertEquals(1, payment.recover(order.id()).consecutiveFailures());
        restoreFind();
        assertResolved(payment.recover(order.id()));
        assertHeld(order);
    }

    @Test
    void concurrentRecoveryCannotClaimTheSameLiveLeaseOrCountOneFaultTwice() throws Exception {
        payment.start(consumer1, order.id(), "same-lease");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var queries = new AtomicInteger();
        doAnswer(call -> {
            queries.incrementAndGet();
            entered.countDown();
            await(release);
            throw new IllegalStateException("test single held recovery fault");
        }).when(channelTarget()).find(anyString());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> payment.recover(order.id()));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                var busy = payment.recover(order.id());
                assertEquals(0, busy.consecutiveFailures());
                assertEquals(NOW.plusSeconds(60), busy.recoveryClaimUntil());
                assertEquals(1, queries.get());
            } finally { release.countDown(); }
            var failed = first.get(20, TimeUnit.SECONDS);
            assertEquals(1, failed.consecutiveFailures());
            assertNull(failed.recoveryClaimUntil());
        } finally { release.countDown(); }
        assertHeld(order);
    }

    @Test
    void expiredLeaseCanBeClaimedAgainAndTheOldWorkerCannotAddAnotherFailure() throws Exception {
        payment.start(consumer1, order.id(), "stale-lease");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var queries = new AtomicInteger();
        doAnswer(call -> {
            if (queries.incrementAndGet() == 1) { entered.countDown(); await(release); }
            throw new IllegalStateException("test old and new recovery fault");
        }).when(channelTarget()).find(anyString());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> payment.recover(order.id()));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                clock.set(NOW.plusSeconds(61));
                var second = payment.recover(order.id());
                assertEquals(2, second.consecutiveFailures(), "expired first claim plus actual second failure");
                assertEquals(NOW.plusSeconds(60), second.firstUnresolvedAt());
                assertEquals(2, queries.get());
            } finally { release.countDown(); }
            var stale = first.get(20, TimeUnit.SECONDS);
            assertEquals(2, stale.consecutiveFailures(), "old token must not count its late failure a third time");
            assertEquals(NOW.plusSeconds(60), stale.firstUnresolvedAt());
            assertNull(stale.recoveryClaimUntil());
        } finally { release.countDown(); }
        assertHeld(order);
    }

    @Test
    void expiredWorkersMayApplyTheSameSuccessFactWithoutOverwritingTheNewClaim() throws Exception {
        var attempt = payment.start(consumer1, order.id(), "stale-success");
        simulator.succeed(attempt.paymentNumber());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var queries = new AtomicInteger();
        doAnswer(call -> {
            var actual = call.callRealMethod();
            if (queries.incrementAndGet() == 1) { entered.countDown(); await(release); }
            return actual;
        }).when(channelTarget()).find(anyString());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> payment.recover(order.id()));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                clock.set(NOW.plusSeconds(61));
                var second = payment.recover(order.id());
                assertEquals(PAID, second.orderStatus());
                assertResolved(second);
            } finally { release.countDown(); }
            var stale = first.get(20, TimeUnit.SECONDS);
            assertEquals(PAID, stale.orderStatus());
            assertResolved(stale);
        } finally { release.countDown(); }
        assertPaid(order);
    }

    @Test
    void channelSuccessAndCancellationCompetitionCannotProducePaidAndReleasedSeats() throws Exception {
        var attempt = payment.start(consumer1, order.id(), "success-cancel");
        compete(() -> paymentOutcome(() -> { payment.applyChannel(simulator.succeed(attempt.paymentNumber())); return "SUCCESS"; }),
                () -> { payment.cancel(consumer1, order.id()); return "CANCEL"; });
        var status = orders.get(consumer1, order.id()).orderStatus();
        if (status == PAID) { assertPaid(order); }
        else { assertEquals(CANCELLED, status); assertCancelled(order, "RELEASED"); }
        var local = payment.payments(consumer1, order.id()).getFirst();
        assertEquals(status == PAID ? "SUCCEEDED" : "CLOSED", local.paymentStatus().name());
    }

    private PaymentSimulatorService channelTarget() { return AopTestUtils.getUltimateTargetObject(simulator); }
    private void failFind() {
        doAnswer(call -> { throw new IllegalStateException("test channel lookup unavailable"); }).when(channelTarget()).find(anyString());
    }
    private void restoreFind() { doAnswer(call -> call.callRealMethod()).when(channelTarget()).find(anyString()); }
    private void assertResolved(PaymentModels.OrderPaymentState state) {
        assertEquals(0, state.consecutiveFailures());
        assertNull(state.firstUnresolvedAt());
        assertNull(state.recentError());
        assertFalse(state.manualRequired());
        assertNull(state.recoveryClaimUntil());
    }
}
