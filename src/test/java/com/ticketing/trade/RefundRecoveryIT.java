package com.ticketing.trade;

import com.ticketing.simulator.RefundSimulatorModels.Notification;
import com.ticketing.simulator.RefundSimulatorService;
import com.ticketing.simulator.RefundSimulatorTransactionService;
import com.ticketing.ticketing.TicketModels.TicketStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.ticketing.trade.RefundModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** SQL faults follow real writes; deterministic Clock/leases model interruption, not process restarts. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({PaymentTestSupport.TestClock.class, PaymentTestSupport.RealMapperSpies.class,
        RefundRecoveryIT.RealRefundMapperSpies.class})
class RefundRecoveryIT extends RefundTestSupport {
    @Autowired private RefundService refunds;
    @Autowired private RefundQueryService refundQueries;
    @Autowired private RefundMapper refundMapper;
    @MockitoSpyBean private RefundTransactionService refundWriter;
    @MockitoSpyBean private RefundSimulatorService refundChannel;
    @MockitoSpyBean private RefundSimulatorTransactionService refundChannelWriter;

    @BeforeEach
    void resetRealRefundMapper() { reset(refundMapper); }

    enum AcceptanceWrite { REFUND_INSERT, TICKET_TRANSITION }
    enum SuccessWrite { REFUND_RESULT, TICKET_INVALIDATION, SEAT_RETURN, ORDER_SUMMARY }
    enum FailureWrite { REFUND_RESULT, TICKET_RESTORE }

    @ParameterizedTest
    @EnumSource(AcceptanceWrite.class)
    void requestAndTicketStateRollBackTogetherAfterEachActualAcceptanceWrite(AcceptanceWrite point)
            throws Exception {
        var ticket = paidTickets.getFirst();
        var originalTicket = jdbc.queryForMap("SELECT * FROM ticket WHERE id=?", ticket.id());
        var injected = new AtomicBoolean();
        if (point == AcceptanceWrite.REFUND_INSERT) {
            doAnswer(call -> {
                int changed = sqlSession.getMapper(RefundMapper.class).insert(call.getArgument(0), call.getArgument(1));
                assertEquals(1, changed);
                assertEquals(1, count("refund_record"), "the INSERT must have reached MySQL before the fault");
                injected.set(true);
                throw new IllegalStateException("test interruption after actual refund INSERT");
            }).when(refundMapper).insert(any(), any());
        } else {
            injectActualTicketTransition(TicketStatus.REFUNDING, injected);
        }

        assertThrows(IllegalStateException.class,
                () -> refundWriter.begin(consumer1, order.id(), ticket.id(), "interrupted-request"));

        assertTrue(injected.get());
        assertEquals(0, count("refund_record"));
        assertEquals(0, count("simulator_refund"));
        assertEquals(originalTicket, jdbc.queryForMap("SELECT * FROM ticket WHERE id=?", ticket.id()));
        assertSeat(ticket, "SOLD");
        reset(refundMapper, ticketMapper);
        var accepted = start("interrupted-request");
        assertEquals(RefundStatus.PROCESSING, accepted.refundStatus());
        assertEquals(1, count("refund_record"));
        assertEquals(1, count("simulator_refund"));
    }

    @ParameterizedTest
    @EnumSource(SuccessWrite.class)
    void everyActualSuccessWriteIsRolledBackWithTheWholeLocalResult(SuccessWrite point) throws Exception {
        var refund = start("success-write-" + point.name());
        var success = terminalNotice(refund, true);
        var before = snapshot(refund);
        var injected = new AtomicBoolean();
        switch (point) {
            case REFUND_RESULT -> injectActualRefundApply(injected, RefundStatus.SUCCEEDED);
            case TICKET_INVALIDATION -> injectActualTicketTransition(TicketStatus.REFUNDED, injected);
            case SEAT_RETURN -> injectActualSeatReturn(injected);
            case ORDER_SUMMARY -> doAnswer(call -> {
                int changed = sqlSession.getMapper(RefundMapper.class).summarize(call.getArgument(0),
                        call.getArgument(1), call.getArgument(2));
                assertEquals(1, changed);
                assertEquals("PARTIALLY_REFUNDED", jdbc.queryForObject(
                        "SELECT order_status FROM customer_order WHERE id=?", String.class, order.id()));
                injected.set(true);
                throw new IllegalStateException("test interruption after actual order summary UPDATE");
            }).when(refundMapper).summarize(anyLong(), any(), any());
        }

        assertThrows(IllegalStateException.class, () -> refundWriter.applyNotification(success));

        assertTrue(injected.get());
        assertEquals(before, snapshot(refund));
        assertEquals("SUCCEEDED", refundChannel.find(refund.refundNumber()).orElseThrow().refundStatus().name());
        assertNull(storedNotice(success).deliveredAt());
        reset(refundMapper, ticketMapper, inventoryHoldMapper);
        refunds.deliver(success.notificationNumber());
        assertSucceeded(refund);
    }

    @ParameterizedTest
    @EnumSource(FailureWrite.class)
    void explicitFailureAndTicketRestorationAlsoRollBackTogetherAfterActualWrites(FailureWrite point)
            throws Exception {
        var refund = start("failed-write-" + point.name());
        var failed = terminalNotice(refund, false);
        var before = snapshot(refund);
        var injected = new AtomicBoolean();
        if (point == FailureWrite.REFUND_RESULT) { injectActualRefundApply(injected, RefundStatus.FAILED); }
        else { injectActualTicketTransition(TicketStatus.VALID, injected); }

        assertThrows(IllegalStateException.class, () -> refundWriter.applyNotification(failed));

        assertTrue(injected.get());
        assertEquals(before, snapshot(refund));
        assertNull(storedNotice(failed).deliveredAt());
        reset(refundMapper, ticketMapper);
        refunds.deliver(failed.notificationNumber());
        assertEquals(RefundStatus.FAILED, refunds.state(refund.id()).refundStatus());
        assertTicket(paidTickets.getFirst(), "VALID");
        assertSeat(paidTickets.getFirst(), "SOLD");
        assertEquals("PAID", orders.get(consumer1, order.id()).orderStatus().name());
    }

    @Test
    void exceptionAtRealCommitRollsBackAllSuccessfulWritesButNotTheIndependentChannel() {
        var refund = start("before-result-commit");
        var success = terminalNotice(refund, true);
        var before = snapshot(refund);
        var injected = new AtomicBoolean();
        doAnswer(call -> {
            var result = call.callRealMethod();
            if (injected.compareAndSet(false, true)) {
                assertTicket(paidTickets.getFirst(), "REFUNDED");
                assertSeat(paidTickets.getFirst(), "AVAILABLE");
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) {
                        throw new IllegalStateException("test interruption at actual local result commit");
                    }
                });
            }
            return result;
        }).when(writerTarget()).applyNotification(any());

        assertThrows(IllegalStateException.class, () -> refundWriter.applyNotification(success));

        assertTrue(injected.get());
        assertEquals(before, snapshot(refund));
        assertEquals("SUCCEEDED", refundChannel.find(refund.refundNumber()).orElseThrow().refundStatus().name());
        assertNull(storedNotice(success).deliveredAt());
        refunds.deliver(success.notificationNumber());
        assertSucceeded(refund);
    }

    @Test
    void unsubmittedRequestIsRecoveredWithItsOriginalNumberEvenAfterAdmissionOpens() {
        var fault = new AtomicBoolean();
        doAnswer(call -> {
            if (fault.compareAndSet(false, true)) { throw new IllegalStateException("test before channel acceptance"); }
            return call.callRealMethod();
        }).when(channelTarget()).start(any());
        var refund = start("unsubmitted-original");
        assertEquals(RefundStatus.REQUESTED, refund.refundStatus());
        assertTrue(refundChannel.find(refund.refundNumber()).isEmpty());
        assertRefundingAndSold();

        clock.set(sessions.get(sessionId).admissionOpenAt());
        var restored = refunds.recover(refund.id());

        assertEquals(refund.refundNumber(), restored.refundNumber());
        assertEquals(RefundStatus.PROCESSING, restored.refundStatus());
        assertEquals(1, count("refund_record"));
        assertEquals(1, count("simulator_refund"));
        assertRefundingAndSold();
    }

    @Test
    void channelAcceptanceCommittedBeforeResponseLossIsFoundRatherThanRefundedAgain() {
        var fault = new AtomicBoolean();
        doAnswer(call -> {
            var result = call.callRealMethod();
            if (fault.compareAndSet(false, true)) { throw new IllegalStateException("test accepted response lost"); }
            return result;
        }).when(channelTarget()).start(any());
        var refund = start("accepted-response-lost");
        assertEquals(RefundStatus.REQUESTED, refund.refundStatus());
        assertEquals(1, count("simulator_refund"));
        assertEquals(1, refundChannel.notifications(refund.refundNumber()).size());

        var restored = refunds.recover(refund.id());

        assertEquals(RefundStatus.PROCESSING, restored.refundStatus());
        assertEquals(refund.refundNumber(), restored.refundNumber());
        assertEquals(1, count("simulator_refund"));
        assertEquals(1, refundChannel.notifications(refund.refundNumber()).size());
        assertEquals(0, restored.consecutiveFailures());
        assertRefundingAndSold();
    }

    @Test
    void staleTokenCannotCompleteOrFailTheNewWorkersClaim() {
        var refund = start("stale-claim");
        var old = refundWriter.claim(refund.id(), true);
        assertNotNull(old);
        clock.set(NOW.plusSeconds(60));
        var current = refundWriter.claim(refund.id(), true);
        assertNotNull(current);
        assertNotEquals(old.token(), current.token());
        assertEquals(1, current.refund().consecutiveFailures());
        assertEquals("RECOVERY_LEASE_EXPIRED", current.refund().recentError());
        var before = jdbc.queryForMap("SELECT * FROM refund_record WHERE id=?", refund.id());

        refundWriter.complete(refund.id(), old.token(), true);
        refundWriter.fail(refund.id(), old.token(), "OLD_WORKER_ERROR");

        assertEquals(before, jdbc.queryForMap("SELECT * FROM refund_record WHERE id=?", refund.id()));
        assertEquals(current.token(), refunds.state(refund.id()).recoveryToken());
        assertRefundingAndSold();
    }

    @Test
    void trustworthySuccessCanBeAppliedWhileAnotherWorkerOwnsTheNewLease() {
        var refund = start("stale-worker-trusted-result");
        var old = refundWriter.claim(refund.id(), true);
        clock.set(NOW.plusSeconds(60));
        var current = refundWriter.claim(refund.id(), true);
        var success = terminalNotice(refund, true);

        // The original channel snapshot is authoritative independently of who fetched it.
        refundChannel.deliver(success.notificationNumber(), refundWriter::applyNotification);
        assertEquals(current.token(), refunds.state(refund.id()).recoveryToken());
        assertEquals(RefundStatus.SUCCEEDED, refunds.state(refund.id()).refundStatus());
        refundWriter.complete(refund.id(), old.token(), true);
        refundWriter.fail(refund.id(), old.token(), "LATE_OLD_ERROR");
        assertEquals(current.token(), refunds.state(refund.id()).recoveryToken());
        refundWriter.complete(refund.id(), current.token(), true);

        assertSucceeded(refund);
        assertNull(refunds.state(refund.id()).nextRecoveryAt());
        assertEquals(0, refunds.state(refund.id()).consecutiveFailures());
    }

    @Test
    void automaticRecoveryRechecksAnActiveLeaseAndDoesNotTouchTheOriginalFacts() {
        var refund = start("active-lease");
        var work = refundWriter.claim(refund.id(), true);
        assertNotNull(work);
        clock.set(NOW.plusSeconds(59));
        var before = jdbc.queryForMap("SELECT * FROM refund_record WHERE id=?", refund.id());

        assertEquals(refunds.state(refund.id()), refunds.recoverIfDue(refund.id()));

        assertEquals(before, jdbc.queryForMap("SELECT * FROM refund_record WHERE id=?", refund.id()));
        assertFalse(refundQueries.due(clock.instant(), 0, refundQueries.upperBound(), 50).contains(refund.id()));
        assertRefundingAndSold();
    }

    @Test
    void actualChannelFailuresUseBackoffAndTheSixthRequiresManualReviewWithoutReturningTheSeat() {
        var refund = start("actual-failure-threshold");
        failChannelFind();
        long[] backoffs = {5, 15, 30, 60, 120, 120};
        for (int failure = 1; failure <= 6; failure++) {
            var at = NOW.plusSeconds(failure - 1);
            clock.set(at);
            var failed = refunds.recover(refund.id());
            assertEquals(failure, failed.consecutiveFailures());
            assertEquals(NOW, failed.firstUnresolvedAt());
            assertEquals(at.plusSeconds(backoffs[failure - 1]), failed.nextRecoveryAt());
            assertEquals(failure == 6, failed.manualRequired());
            assertNull(failed.recoveryToken());
            assertRefundingAndSold();
        }
        var before = jdbc.queryForMap("SELECT * FROM refund_record WHERE id=?", refund.id());
        clock.set(NOW.plusSeconds(1000));
        refunds.recoverIfDue(refund.id());
        assertEquals(before, jdbc.queryForMap("SELECT * FROM refund_record WHERE id=?", refund.id()));

        restoreChannelFind();
        var restored = refunds.recover(refund.id());
        assertEquals(0, restored.consecutiveFailures());
        assertFalse(restored.manualRequired());
        assertRefundingAndSold();
    }

    @Test
    void automaticRecoveryCannotBypassPersistedBackoffEvenIfItsCandidateWasAlreadySelected() {
        var refund = start("backoff-recheck");
        failChannelFind();
        var failed = refunds.recover(refund.id());
        assertEquals(NOW.plusSeconds(5), failed.nextRecoveryAt());
        restoreChannelFind();
        clock.set(NOW.plusSeconds(4));
        var before = jdbc.queryForMap("SELECT * FROM refund_record WHERE id=?", refund.id());

        refunds.recoverIfDue(refund.id());

        assertEquals(before, jdbc.queryForMap("SELECT * FROM refund_record WHERE id=?", refund.id()));
        clock.set(NOW.plusSeconds(5));
        assertEquals(0, refunds.recoverIfDue(refund.id()).consecutiveFailures());
        assertRefundingAndSold();
    }

    @Test
    void tenMinutesOfActualFailuresAlsoRequiresManualReviewBeforeTheSixthAttempt() {
        var refund = start("actual-duration-threshold");
        failChannelFind();
        assertEquals(1, refunds.recover(refund.id()).consecutiveFailures());
        clock.set(NOW.plusSeconds(600));

        var paused = refunds.recover(refund.id());

        assertEquals(2, paused.consecutiveFailures());
        assertTrue(paused.manualRequired());
        assertEquals(NOW, paused.firstUnresolvedAt());
        assertRefundingAndSold();
    }

    @Test
    void sixthAbandonedLeaseIsPausedInsideAutomaticClaimBeforeAnyChannelInteraction() {
        var refund = start("sixth-abandoned-lease");
        failChannelFind();
        for (int failure = 1; failure <= 5; failure++) {
            assertEquals(failure, refunds.recover(refund.id()).consecutiveFailures());
        }
        restoreChannelFind();
        var abandoned = refundWriter.claim(refund.id(), true);
        assertNotNull(abandoned);
        clock.set(NOW.plusSeconds(60));

        var paused = refunds.recoverIfDue(refund.id());

        assertEquals(6, paused.consecutiveFailures());
        assertTrue(paused.manualRequired());
        assertEquals("RECOVERY_LEASE_EXPIRED", paused.recentError());
        assertNull(paused.recoveryToken());
        assertRefundingAndSold();
    }

    @Test
    void committedTerminalResultStaysDiscoverableUntilItsOriginalNotificationIsAcknowledged() {
        var refund = start("terminal-unacknowledged");
        var success = terminalNotice(refund, true);
        refundWriter.applyNotification(success); // C commits, D has not run.
        assertEquals(RefundStatus.SUCCEEDED, refunds.state(refund.id()).refundStatus());
        assertNotNull(refunds.state(refund.id()).nextRecoveryAt());
        assertTrue(refundQueries.due(clock.instant(), 0, refundQueries.upperBound(), 50).contains(refund.id()));
        assertNull(storedNotice(success).deliveredAt());
        var fault = new AtomicBoolean();
        doAnswer(call -> {
            if (fault.compareAndSet(false, true)) { throw new IllegalStateException("test acknowledgement unavailable"); }
            return call.callRealMethod();
        }).when(channelWriterTarget()).acknowledge(eq(success.notificationNumber()));
        var scanner = new RefundRecoveryScanner(refundQueries, refunds, clock, 50);

        assertEquals(1, scanner.scanOnce());

        assertTrue(fault.get());
        assertEquals(RefundStatus.SUCCEEDED, refunds.state(refund.id()).refundStatus());
        assertNull(storedNotice(success).deliveredAt());
        assertNotNull(refunds.state(refund.id()).nextRecoveryAt());
        clock.set(refunds.state(refund.id()).nextRecoveryAt());
        assertEquals(1, scanner.scanOnce());
        assertSucceeded(refund);
        assertNotNull(storedNotice(success).deliveredAt());
        assertNull(refunds.state(refund.id()).nextRecoveryAt());
        assertTrue(refundQueries.due(clock.instant(), 0, refundQueries.upperBound(), 50).isEmpty());
    }

    @Test
    void acknowledgedNotificationSurvivesALaterSettlementRollbackAndIsNotAppliedTwice() {
        var refund = start("acknowledged-settlement-fault");
        var success = terminalNotice(refund, true);
        var fault = new AtomicBoolean();
        doAnswer(call -> {
            call.callRealMethod();
            if (fault.compareAndSet(false, true)) {
                assertNotNull(storedNotice(success).deliveredAt(), "D must already be independently committed");
                assertNull(jdbc.queryForMap("SELECT next_recovery_at FROM refund_record WHERE id=?", refund.id())
                                .get("next_recovery_at"),
                        "the actual settlement UPDATE must have run before its rollback");
                throw new IllegalStateException("test settlement interrupted after actual UPDATE");
            }
            return null;
        }).when(writerTarget()).complete(eq(refund.id()), anyString(), eq(true));

        var interrupted = refunds.recover(refund.id());

        assertTrue(fault.get());
        assertEquals(RefundStatus.SUCCEEDED, interrupted.refundStatus());
        assertNotNull(storedNotice(success).deliveredAt());
        assertNotNull(interrupted.nextRecoveryAt());
        var ticketBefore = jdbc.queryForMap("SELECT * FROM ticket WHERE id=?", refund.ticketId());
        var seatBefore = jdbc.queryForMap("SELECT * FROM session_seat WHERE id=?", refund.sessionSeatId());
        clock.set(interrupted.nextRecoveryAt());
        refunds.recoverIfDue(refund.id());
        assertNull(refunds.state(refund.id()).nextRecoveryAt());
        assertEquals(ticketBefore, jdbc.queryForMap("SELECT * FROM ticket WHERE id=?", refund.ticketId()));
        assertEquals(seatBefore, jdbc.queryForMap("SELECT * FROM session_seat WHERE id=?", refund.sessionSeatId()));
    }

    private RefundRecord start(String key) {
        return refunds.start(consumer1, order.id(), paidTickets.getFirst().id(), key);
    }

    private Notification terminalNotice(RefundRecord refund, boolean success) {
        if (success) { refundChannel.succeed(refund.refundNumber()); }
        else { refundChannel.fail(refund.refundNumber()); }
        String status = success ? "SUCCEEDED" : "FAILED";
        return refundChannel.notifications(refund.refundNumber()).stream()
                .filter(n -> n.refund().refundStatus().name().equals(status)).findFirst().orElseThrow();
    }

    private Notification storedNotice(Notification notice) {
        return refundChannel.notifications(notice.refund().refundNumber()).stream()
                .filter(n -> n.notificationNumber().equals(notice.notificationNumber())).findFirst().orElseThrow();
    }

    private void assertRefundingAndSold() {
        assertTicket(paidTickets.getFirst(), "REFUNDING");
        assertSeat(paidTickets.getFirst(), "SOLD");
        assertTicket(paidTickets.getLast(), "VALID");
        assertSeat(paidTickets.getLast(), "SOLD");
    }

    private void assertSucceeded(RefundRecord refund) {
        assertEquals(RefundStatus.SUCCEEDED, refunds.state(refund.id()).refundStatus());
        assertTicket(paidTickets.getFirst(), "REFUNDED");
        assertSeat(paidTickets.getFirst(), "AVAILABLE");
        assertTicket(paidTickets.getLast(), "VALID");
        assertSeat(paidTickets.getLast(), "SOLD");
        assertEquals("PARTIALLY_REFUNDED", orders.get(consumer1, order.id()).orderStatus().name());
        assertEquals("SUCCEEDED", payment.payments(consumer1, order.id()).getFirst().paymentStatus().name());
        assertEquals(2, count("ticket"));
    }

    private Snapshot snapshot(RefundRecord refund) {
        return new Snapshot(jdbc.queryForMap("SELECT * FROM refund_record WHERE id=?", refund.id()),
                jdbc.queryForMap("SELECT * FROM customer_order WHERE id=?", refund.orderId()),
                jdbc.queryForMap("SELECT * FROM ticket WHERE id=?", refund.ticketId()),
                jdbc.queryForMap("SELECT * FROM session_seat WHERE id=?", refund.sessionSeatId()));
    }

    private void injectActualRefundApply(AtomicBoolean injected, RefundStatus expected) {
        doAnswer(call -> {
            int changed = sqlSession.getMapper(RefundMapper.class).apply(call.getArgument(0), call.getArgument(1),
                    call.getArgument(2), call.getArgument(3), call.getArgument(4), call.getArgument(5));
            assertEquals(1, changed);
            assertEquals(expected.name(), jdbc.queryForObject("SELECT refund_status FROM refund_record WHERE id=?",
                    String.class, call.<Long>getArgument(0)));
            injected.set(true);
            throw new IllegalStateException("test interruption after actual refund result UPDATE");
        }).when(refundMapper).apply(anyLong(), any(), any(), any(), any(), any());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void injectActualTicketTransition(TicketStatus expectedTarget, AtomicBoolean injected) throws Exception {
        var type = Class.forName("com.ticketing.ticketing.TicketMapper");
        Method method = Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals("transition"))
                .findFirst().orElseThrow();
        method.setAccessible(true);
        var stubbed = doAnswer(call -> {
            int changed = (int) method.invoke(sqlSession.getMapper((Class) type), call.getArguments());
            assertEquals(1, changed);
            assertEquals(expectedTarget.name(), jdbc.queryForObject("SELECT ticket_status FROM ticket WHERE id=?",
                    String.class, paidTickets.getFirst().id()));
            injected.set(true);
            throw new IllegalStateException("test interruption after actual ticket transition UPDATE");
        }).when(ticketMapper);
        method.invoke(stubbed, anyLong(), any(TicketStatus.class), any(TicketStatus.class), any(Instant.class));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void injectActualSeatReturn(AtomicBoolean injected) throws Exception {
        var type = Class.forName("com.ticketing.inventory.InventoryHoldMapper");
        Method method = Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals("refundSeat"))
                .findFirst().orElseThrow();
        method.setAccessible(true);
        var stubbed = doAnswer(call -> {
            int changed = (int) method.invoke(sqlSession.getMapper((Class) type), call.getArguments());
            assertEquals(1, changed);
            assertSeat(paidTickets.getFirst(), "AVAILABLE");
            injected.set(true);
            throw new IllegalStateException("test interruption after actual returned seat UPDATE");
        }).when(inventoryHoldMapper);
        method.invoke(stubbed, anyLong(), anyLong(), any(Instant.class));
    }

    private void failChannelFind() {
        doAnswer(call -> { throw new IllegalStateException("test actual channel lookup unavailable"); })
                .when(channelTarget()).find(anyString());
    }
    private void restoreChannelFind() {
        doAnswer(call -> call.callRealMethod()).when(channelTarget()).find(anyString());
    }
    private RefundSimulatorService channelTarget() { return AopTestUtils.getUltimateTargetObject(refundChannel); }
    private RefundSimulatorTransactionService channelWriterTarget() {
        return AopTestUtils.getUltimateTargetObject(refundChannelWriter);
    }
    private RefundTransactionService writerTarget() { return AopTestUtils.getUltimateTargetObject(refundWriter); }

    private record Snapshot(Map<String, Object> refund, Map<String, Object> order,
                            Map<String, Object> ticket, Map<String, Object> seat) { }

    @TestConfiguration
    static class RealRefundMapperSpies {
        @Bean
        static BeanPostProcessor refundFaultMapperSpies() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return beanName.equals("refundMapper") && bean instanceof RefundMapper ? spy(bean) : bean;
                }
            };
        }
    }
}
