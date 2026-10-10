package com.ticketing.simulator;

import com.ticketing.common.web.BusinessException;
import com.ticketing.simulator.PaymentSimulatorModels.Binding;
import com.ticketing.simulator.PaymentSimulatorModels.Notification;
import com.ticketing.simulator.PaymentSimulatorModels.Payment;
import com.ticketing.support.Week2Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.ticketing.simulator.PaymentSimulatorModels.PaymentStatus.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

/** Real isolated MySQL transactions; no merchant payment, order, ticket, or automatic recovery assumptions. */
@SpringBootTest
@ActiveProfiles("test")
@Import(PaymentSimulatorIT.TestClock.class)
class PaymentSimulatorIT {
    private static final Instant NOW = Instant.parse("2026-10-10T02:00:00.123456Z");
    private static final BigDecimal AMOUNT = new BigDecimal("760.00");
    @Autowired private PaymentSimulatorService simulator;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SqlSessionTemplate sqlSession;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private MutableClock clock;
    @MockitoSpyBean private PaymentSimulatorMapper mapper;

    @BeforeEach
    void prepare() {
        clock.set(NOW);
        Week2Fixtures.resetTestDatabase(jdbc);
    }

    @Test
    void startPersistsTheChannelFactAndItsOwnNotificationWithoutMerchantRows() {
        var request = binding("normal");
        var payment = simulator.start(request);
        assertEquals(PROCESSING, payment.paymentStatus());
        assertEquals(request.paymentNumber(), payment.paymentNumber());
        assertEquals(request.merchantOrderNumber(), payment.merchantOrderNumber());
        assertEquals(AMOUNT, payment.amount());
        assertEquals(request.paymentExpiresAt(), payment.paymentExpiresAt());
        assertEquals(NOW, payment.acceptedAt());
        assertEquals(NOW, payment.createdAt());
        assertEquals(NOW, payment.updatedAt());
        assertNull(payment.resultAt());
        assertNull(payment.failureCode());
        assertEquals(payment, simulator.find(request.paymentNumber()).orElseThrow());
        var notice = onlyNotice(request.paymentNumber());
        assertEquals(PROCESSING, notice.observedStatus());
        assertEquals(NOW, notice.observedAt());
        assertNull(notice.deliveredAt());
        assertEquals(1, count("simulated_payment"));
        for (var table : List.of("customer_order", "order_item", "seat_hold", "account")) {
            assertEquals(0, count(table), "simulator must not manufacture " + table);
        }
        assertNoReservedRows();
    }

    @Test
    void identicalReplayPreservesTheOriginalTimesAndDoesNotCreateAnotherNotification() {
        var request = binding("replay");
        var first = simulator.start(request);
        clock.set(NOW.plusSeconds(30));
        assertEquals(first, simulator.start(request));
        assertEquals(first, simulator.start(new Binding(request.paymentNumber(), request.merchantOrderNumber(),
                new BigDecimal("760.0"), request.paymentExpiresAt())));
        assertEquals(1, simulator.notifications(request.paymentNumber()).size());
        assertEquals(1, count("simulated_payment"));
    }

    @Test
    void bindingConflictsRejectChangedMerchantOrderAmountAndOriginalDeadlineEvenAfterClosure() {
        var request = binding("bound");
        var original = simulator.start(request);
        assertBindingConflicts(request);
        assertEquals(original, simulator.find(request.paymentNumber()).orElseThrow());
        var closed = simulator.close(request);
        assertEquals(CLOSED, closed.paymentStatus());
        assertBindingConflicts(request);
        assertEquals(closed, simulator.find(request.paymentNumber()).orElseThrow());
        assertEquals(2, simulator.notifications(request.paymentNumber()).size());
    }

    @Test
    void firstAcceptanceRequiresStrictlyBeforeTheDeadlineAtDatabaseMicrosecondPrecision() {
        var deadline = NOW.plusSeconds(1);
        clock.set(deadline.minusNanos(1_000));
        var before = simulator.start(binding("before", deadline));
        assertEquals(PROCESSING, before.paymentStatus());
        assertEquals(deadline.minusNanos(1_000), before.acceptedAt());
        for (var at : List.of(deadline, deadline.plusNanos(1_000))) {
            clock.set(at);
            var number = at.equals(deadline) ? "equal" : "after";
            var failed = simulator.start(binding(number, deadline));
            assertEquals(FAILED, failed.paymentStatus());
            assertEquals("EXPIRED_BEFORE_ACCEPTANCE", failed.failureCode());
            assertNull(failed.acceptedAt());
            assertEquals(at, failed.resultAt());
            assertEquals(FAILED, onlyNotice(number).observedStatus());
            clock.set(at.plusSeconds(20));
            assertEquals(failed, simulator.start(binding(number, deadline)));
            assertEquals(1, simulator.notifications(number).size());
        }
        assertNoReservedRows();
    }

    @Test
    void acceptedPaymentCanBeReplayedAndSucceededAfterItsOriginalDeadline() {
        var request = binding("late-result", NOW.plusSeconds(1));
        var accepted = simulator.start(request);
        clock.set(NOW.plusSeconds(10));
        assertEquals(accepted, simulator.start(request));
        var paid = simulator.succeed(request.paymentNumber());
        assertEquals(SUCCEEDED, paid.paymentStatus());
        assertEquals(NOW, paid.acceptedAt());
        assertEquals(NOW.plusSeconds(10), paid.resultAt());
        assertEquals(paid, simulator.start(request));
        assertEquals(paid, simulator.close(request));
        assertEquals(2, simulator.notifications(request.paymentNumber()).size());
    }

    @Test
    void closingAMissingNumberPersistsATombstoneThatLateStartCannotReopen() {
        var request = binding("not-submitted");
        assertTrue(simulator.find(request.paymentNumber()).isEmpty());
        var closed = simulator.close(request);
        assertEquals(CLOSED, closed.paymentStatus());
        assertNull(closed.acceptedAt());
        assertEquals(NOW, closed.resultAt());
        clock.set(NOW.plusSeconds(2));
        assertEquals(closed, simulator.start(request));
        assertEquals(closed, simulator.close(request));
        assertEquals(1, simulator.notifications(request.paymentNumber()).size());
        assertConflict("SIMULATOR_STATE_CONFLICT", () -> simulator.succeed(request.paymentNumber()));
        assertConflict("SIMULATOR_STATE_CONFLICT", () -> simulator.fail(request.paymentNumber()));
        assertEquals(closed, simulator.find(request.paymentNumber()).orElseThrow());
        assertNoReservedRows();
    }

    @Test
    void failedIsAnImmutableTerminalAndSafeCloseDoesNotRewriteItAsClosed() {
        var request = binding("failed");
        simulator.start(request);
        clock.set(NOW.plusSeconds(3));
        var failed = simulator.fail(request.paymentNumber());
        assertEquals(FAILED, failed.paymentStatus());
        assertEquals("SIMULATED_FAILURE", failed.failureCode());
        clock.set(NOW.plusSeconds(5));
        assertEquals(failed, simulator.fail(request.paymentNumber()));
        assertEquals(failed, simulator.close(request));
        assertEquals(failed, simulator.start(request));
        assertConflict("SIMULATOR_STATE_CONFLICT", () -> simulator.succeed(request.paymentNumber()));
        assertEquals(2, simulator.notifications(request.paymentNumber()).size());
    }

    @Test
    void succeededCannotBeReplacedByFailedAndRepeatSuccessIsIdempotent() {
        var request = binding("paid");
        simulator.start(request);
        var paid = simulator.succeed(request.paymentNumber());
        clock.set(NOW.plusSeconds(3));
        assertEquals(paid, simulator.succeed(request.paymentNumber()));
        assertEquals(paid, simulator.close(request));
        assertConflict("SIMULATOR_STATE_CONFLICT", () -> simulator.fail(request.paymentNumber()));
        assertEquals(paid, simulator.find(request.paymentNumber()).orElseThrow());
        assertEquals(2, simulator.notifications(request.paymentNumber()).size());
    }

    @Test
    void unknownLookupsReturnEmptyAndStateOrNotificationCommandsReturn404() {
        assertTrue(simulator.find("missing").isEmpty());
        assertStatus(HttpStatus.NOT_FOUND, () -> simulator.succeed("missing"));
        assertStatus(HttpStatus.NOT_FOUND, () -> simulator.fail("missing"));
        assertStatus(HttpStatus.NOT_FOUND, () -> simulator.notifications("missing"));
        assertStatus(HttpStatus.NOT_FOUND, () -> simulator.createNotification("missing"));
        assertStatus(HttpStatus.NOT_FOUND, () -> simulator.deliver("missing-notice", notice -> fail("unexpected delivery")));
        assertEquals(0, count("simulated_payment"));
        assertEquals(0, count("simulated_notification"));
    }

    @Test
    void invalidAmountsAndIdentifiersAreRejectedBeforeAnyChannelFactIsSaved() {
        var request = binding("invalid");
        for (var invalidAmount : List.of(BigDecimal.ZERO, new BigDecimal("-1.00"),
                new BigDecimal("760.001"), new BigDecimal("10000000000.00"))) {
            assertStatus(HttpStatus.BAD_REQUEST, () -> simulator.start(new Binding(request.paymentNumber(),
                    request.merchantOrderNumber(), invalidAmount, request.paymentExpiresAt())));
        }
        for (var invalidNumber : List.of("", "has whitespace", "非ASCII", "x".repeat(37))) {
            assertStatus(HttpStatus.BAD_REQUEST, () -> simulator.start(new Binding(invalidNumber,
                    request.merchantOrderNumber(), AMOUNT, request.paymentExpiresAt())));
            assertStatus(HttpStatus.BAD_REQUEST, () -> simulator.close(new Binding(request.paymentNumber(),
                    invalidNumber, AMOUNT, request.paymentExpiresAt())));
        }
        assertEquals(0, count("simulated_payment"));
        assertEquals(0, count("simulated_notification"));
    }

    @Test
    void paymentNumbersAreCaseSensitiveAndNeverCollapsedToAnotherChannelTransaction() {
        var upper = simulator.start(binding("Case-Sensitive"));
        var lower = simulator.close(binding("case-sensitive"));
        assertEquals(PROCESSING, upper.paymentStatus());
        assertEquals(CLOSED, lower.paymentStatus());
        assertEquals(upper, simulator.find("Case-Sensitive").orElseThrow());
        assertEquals(lower, simulator.find("case-sensitive").orElseThrow());
        assertEquals(2, count("simulated_payment"));
        assertEquals(2, count("simulated_notification"));
    }

    @Test
    void twoFirstStartsMeetAtTheGenuineInsertAndRecoverOneCommittedWinner() throws Exception {
        var request = binding("first-start-race");
        var inserts = synchronizeFirstReservationInserts();
        var results = compete(() -> simulator.start(request), () -> simulator.start(request));
        assertEquals(results.getFirst(), results.getLast());
        assertEquals(PROCESSING, results.getFirst().paymentStatus());
        assertEquals(2, inserts.get(), "both missing-row paths must attempt the actual unique INSERT");
        assertEquals(1, count("simulated_payment"));
        assertEquals(1, count("simulated_notification"));
        assertNoReservedRows();
    }

    @Test
    void firstStartAndFirstCloseMeetAtTheGenuineInsertWithoutOppositeChannelFacts() throws Exception {
        var request = binding("first-close-race");
        var inserts = synchronizeFirstReservationInserts();
        var results = compete(() -> simulator.start(request), () -> simulator.close(request));
        var started = results.getFirst();
        var closed = results.getLast();
        assertTrue(started.paymentStatus() == PROCESSING || started.paymentStatus() == CLOSED);
        assertEquals(CLOSED, closed.paymentStatus());
        assertEquals(closed, simulator.find(request.paymentNumber()).orElseThrow());
        assertEquals(2, inserts.get());
        assertEquals(1, count("simulated_payment"));
        assertEquals(started.paymentStatus() == PROCESSING ? 2 : 1, simulator.notifications(request.paymentNumber()).size());
        assertConflict("SIMULATOR_STATE_CONFLICT", () -> simulator.succeed(request.paymentNumber()));
        assertNoReservedRows();
    }

    @Test
    void processingSuccessAndCloseCompeteOnTheSameRealRowAndOnlyOneTerminalWins() throws Exception {
        var request = binding("success-close-race");
        simulator.start(request);
        synchronizeFirstPaymentLocks();
        var results = compete(() -> outcome(() -> simulator.succeed(request.paymentNumber())),
                () -> outcome(() -> simulator.close(request)));
        var finalPayment = simulator.find(request.paymentNumber()).orElseThrow();
        assertTrue(finalPayment.paymentStatus() == SUCCEEDED || finalPayment.paymentStatus() == CLOSED);
        if (finalPayment.paymentStatus() == SUCCEEDED) {
            assertEquals(finalPayment, results.getFirst().payment());
            assertEquals(finalPayment, results.getLast().payment());
        } else {
            assertEquals("SIMULATOR_STATE_CONFLICT", results.getFirst().error().getCode());
            assertEquals(HttpStatus.CONFLICT, results.getFirst().error().getStatus());
            assertEquals(finalPayment, results.getLast().payment());
        }
        assertEquals(2, simulator.notifications(request.paymentNumber()).size());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM simulated_notification WHERE payment_number=? AND observed_status IN ('SUCCEEDED','CLOSED')",
                Integer.class, request.paymentNumber()));
    }

    @Test
    void acceptanceTimeIsReadAfterWaitingForTheNumberSoCrossingTheDeadlineRejects() throws Exception {
        var request = binding("wait-cutoff", NOW.plusSeconds(1));
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var attempting = new CountDownLatch(1);
        doAnswer(call -> {
            attempting.countDown();
            return sqlSession.getMapper(PaymentSimulatorMapper.class).lockPayment(call.getArgument(0));
        }).when(mapper).lockPayment(eq(request.paymentNumber()));
        doAnswer(call -> {
            attempting.countDown();
            return sqlSession.getMapper(PaymentSimulatorMapper.class).insertReserved(call.getArgument(0), call.getArgument(1));
        }).when(mapper).insertReserved(eq(request), any());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var holder = pool.submit(() -> transaction().execute(status -> {
                insertReservedForLock(request);
                held.countDown();
                await(release);
                status.setRollbackOnly();
                return null;
            }));
            assertTrue(held.await(10, TimeUnit.SECONDS));
            var starting = pool.submit(() -> simulator.start(request));
            try {
                assertTrue(attempting.await(10, TimeUnit.SECONDS));
                clock.set(request.paymentExpiresAt());
            } finally {
                release.countDown();
            }
            holder.get(20, TimeUnit.SECONDS);
            var payment = starting.get(20, TimeUnit.SECONDS);
            assertEquals(FAILED, payment.paymentStatus());
            assertEquals("EXPIRED_BEFORE_ACCEPTANCE", payment.failureCode());
            assertNull(payment.acceptedAt());
            assertEquals(request.paymentExpiresAt(), payment.resultAt());
        } finally {
            release.countDown();
        }
        assertEquals(1, count("simulated_payment"));
        assertEquals(1, count("simulated_notification"));
        assertNoReservedRows();
    }

    @Test
    void faultAfterTheActualReservedInsertLeavesNeitherReservationNorNotification() {
        var request = binding("reservation-fault");
        doAnswer(call -> {
            int changed = sqlSession.getMapper(PaymentSimulatorMapper.class).insertReserved(call.getArgument(0), call.getArgument(1));
            assertEquals(1, changed);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM simulated_payment WHERE payment_status='RESERVED'", Integer.class));
            throw new IllegalStateException("fault after real RESERVED insertion");
        }).when(mapper).insertReserved(eq(request), any());
        assertThrows(IllegalStateException.class, () -> simulator.start(request));
        assertTrue(simulator.find(request.paymentNumber()).isEmpty());
        assertEquals(0, count("simulated_payment"));
        assertEquals(0, count("simulated_notification"));
        doAnswer(call -> sqlSession.getMapper(PaymentSimulatorMapper.class).insertReserved(call.getArgument(0), call.getArgument(1)))
                .when(mapper).insertReserved(eq(request), any());
        assertEquals(PROCESSING, simulator.start(request).paymentStatus());
        assertEquals(1, count("simulated_payment"));
        assertEquals(1, count("simulated_notification"));
        assertNoReservedRows();
    }

    @Test
    void faultAfterActualAcceptanceNotificationRollsBackTheEntireNewChannelTransaction() {
        var request = binding("acceptance-fault");
        failAfterActualNotificationInsert();
        assertThrows(IllegalStateException.class, () -> simulator.start(request));
        assertTrue(simulator.find(request.paymentNumber()).isEmpty());
        assertEquals(0, count("simulated_payment"));
        assertEquals(0, count("simulated_notification"));
        assertNoReservedRows();
        restoreNotificationInsert();
        assertEquals(PROCESSING, simulator.start(request).paymentStatus());
        assertEquals(1, count("simulated_payment"));
        assertEquals(1, count("simulated_notification"));
    }

    @Test
    void faultAfterActualSuccessNotificationRollsBackStateAndSnapshotTogether() {
        var request = binding("success-fault");
        var original = simulator.start(request);
        failAfterActualNotificationInsert();
        assertThrows(IllegalStateException.class, () -> simulator.succeed(request.paymentNumber()));
        assertEquals(original, simulator.find(request.paymentNumber()).orElseThrow());
        assertEquals(1, count("simulated_notification"));
        assertEquals(PROCESSING, onlyNotice(request.paymentNumber()).observedStatus());
        restoreNotificationInsert();
        var paid = simulator.succeed(request.paymentNumber());
        assertEquals(SUCCEEDED, paid.paymentStatus());
        assertEquals(paid, simulator.succeed(request.paymentNumber()));
        assertEquals(2, count("simulated_notification"));
    }

    @Test
    void faultAfterActualCloseNotificationRollsBackStateAndSnapshotTogether() {
        var request = binding("close-fault");
        var original = simulator.start(request);
        failAfterActualNotificationInsert();
        assertThrows(IllegalStateException.class, () -> simulator.close(request));
        assertEquals(original, simulator.find(request.paymentNumber()).orElseThrow());
        assertEquals(1, count("simulated_notification"));
        assertEquals(PROCESSING, onlyNotice(request.paymentNumber()).observedStatus());
        restoreNotificationInsert();
        var closed = simulator.close(request);
        assertEquals(CLOSED, closed.paymentStatus());
        assertEquals(closed, simulator.close(request));
        assertEquals(2, count("simulated_notification"));
    }

    @Test
    void channelStateAndNotificationsCommitIndependentlyOfTheCallingTransactionRollback() {
        var request = binding("independent");
        assertThrows(IllegalStateException.class, () -> transaction().execute(status -> {
            simulator.start(request);
            simulator.succeed(request.paymentNumber());
            throw new IllegalStateException("merchant work rolls back after channel commit");
        }));
        assertEquals(SUCCEEDED, simulator.find(request.paymentNumber()).orElseThrow().paymentStatus());
        assertEquals(2, simulator.notifications(request.paymentNumber()).size());
        assertNoReservedRows();
    }

    @Test
    void persistedSnapshotsAllowOldProcessingAfterSuccessAndDifferentSuccessNotices() {
        var request = binding("notice-order");
        simulator.start(request);
        var oldProcessing = onlyNotice(request.paymentNumber());
        clock.set(NOW.plusSeconds(1));
        simulator.succeed(request.paymentNumber());
        var success = simulator.notifications(request.paymentNumber()).stream().filter(n -> n.observedStatus() == SUCCEEDED).findFirst().orElseThrow();
        clock.set(NOW.plusSeconds(2));
        var secondSuccess = simulator.createNotification(request.paymentNumber());
        assertNotEquals(success.notificationNumber(), secondSuccess.notificationNumber());
        assertEquals(SUCCEEDED, secondSuccess.observedStatus());
        var received = new ArrayList<Notification>();
        simulator.deliver(success.notificationNumber(), received::add);
        simulator.deliver(oldProcessing.notificationNumber(), received::add);
        simulator.deliver(secondSuccess.notificationNumber(), received::add);
        assertEquals(List.of(SUCCEEDED, PROCESSING, SUCCEEDED), received.stream().map(Notification::observedStatus).toList());
        var savedOld = simulator.notifications(request.paymentNumber()).stream().filter(n -> n.notificationNumber().equals(oldProcessing.notificationNumber())).findFirst().orElseThrow();
        assertEquals(PROCESSING, savedOld.observedStatus());
        assertEquals(NOW, savedOld.observedAt());
        assertEquals(SUCCEEDED, simulator.find(request.paymentNumber()).orElseThrow().paymentStatus());
        assertEquals(3, count("simulated_notification"));
    }

    @Test
    void deliveryFailureKeepsTheSameNotificationUndeliveredAndSuccessfulReplayKeepsFirstDeliveryTime() {
        var request = binding("delivery-retry");
        simulator.start(request);
        var notice = onlyNotice(request.paymentNumber());
        clock.set(NOW.plusSeconds(1));
        assertThrows(IllegalStateException.class, () -> simulator.deliver(notice.notificationNumber(), n -> {
            assertEquals(notice, n);
            throw new IllegalStateException("local result transaction did not commit");
        }));
        assertEquals(notice, onlyNotice(request.paymentNumber()));
        var received = new ArrayList<Notification>();
        clock.set(NOW.plusSeconds(2));
        simulator.deliver(notice.notificationNumber(), received::add);
        var delivered = onlyNotice(request.paymentNumber());
        assertEquals(NOW.plusSeconds(2), delivered.deliveredAt());
        clock.set(NOW.plusSeconds(3));
        simulator.deliver(notice.notificationNumber(), received::add);
        assertEquals(2, received.size(), "replaying delivered notifications remains possible");
        assertTrue(received.stream().allMatch(n -> n.notificationNumber().equals(notice.notificationNumber())));
        assertEquals(delivered, onlyNotice(request.paymentNumber()), "replay must retain the first acknowledgement time");
        assertEquals(1, count("simulated_notification"));
    }

    @Test
    void notificationConsumerRunsOutsideChannelDatabaseTransactions() {
        var request = binding("callback-boundary");
        simulator.start(request);
        var notice = onlyNotice(request.paymentNumber());
        simulator.deliver(notice.notificationNumber(), n -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),
                    "merchant receiver must not join a locked simulator transaction");
            assertEquals(PROCESSING, simulator.find(request.paymentNumber()).orElseThrow().paymentStatus());
        });
        assertEquals(NOW, onlyNotice(request.paymentNumber()).deliveredAt());
    }

    @Test
    void mysqlChecksRejectImpossiblePaymentStateAcceptanceAndResultCombinations() {
        var request = binding("payment-checks");
        var original = simulator.start(request);
        for (var mutation : List.of(
                "amount=0", "payment_number=''", "merchant_order_number=''", "payment_status='UNKNOWN'",
                "accepted_at=NULL", "accepted_at=payment_expires_at", "result_at=created_at",
                "failure_code='NOT_A_FAILURE'", "payment_status='SUCCEEDED'",
                "payment_status='SUCCEEDED',result_at=DATE_SUB(accepted_at,INTERVAL 1 MICROSECOND)",
                "payment_status='FAILED',result_at=created_at,failure_code=NULL")) {
            assertSqlCode(3819, () -> jdbc.update("UPDATE simulated_payment SET " + mutation + " WHERE payment_number=?", request.paymentNumber()));
        }
        assertEquals(original, simulator.find(request.paymentNumber()).orElseThrow());
        assertEquals(1, count("simulated_notification"));
    }

    @Test
    void mysqlNotificationForeignKeyStateAndTimeChecksCannotBeBypassed() {
        var request = binding("notification-checks");
        simulator.start(request);
        var notice = onlyNotice(request.paymentNumber());
        assertSqlCode(1452, () -> jdbc.update("UPDATE simulated_notification SET payment_number='no-such-payment' WHERE notification_number=?",
                notice.notificationNumber()));
        assertSqlCode(3819, () -> jdbc.update("UPDATE simulated_notification SET observed_status='RESERVED' WHERE notification_number=?",
                notice.notificationNumber()));
        assertSqlCode(3819, () -> jdbc.update("UPDATE simulated_notification SET delivered_at=? WHERE notification_number=?",
                Timestamp.from(NOW.minusNanos(1_000)), notice.notificationNumber()));
        assertSqlCode(3819, () -> jdbc.update("UPDATE simulated_notification SET notification_number='' WHERE notification_number=?",
                notice.notificationNumber()));
        assertEquals(notice, onlyNotice(request.paymentNumber()));
    }

    private AtomicInteger synchronizeFirstReservationInserts() {
        var both = new CyclicBarrier(2);
        var attempts = new AtomicInteger();
        doAnswer(call -> {
            if (attempts.incrementAndGet() <= 2) { both.await(10, TimeUnit.SECONDS); }
            return sqlSession.getMapper(PaymentSimulatorMapper.class).insertReserved(call.getArgument(0), call.getArgument(1));
        }).when(mapper).insertReserved(any(), any());
        return attempts;
    }

    private void synchronizeFirstPaymentLocks() {
        var both = new CyclicBarrier(2);
        var locks = new AtomicInteger();
        doAnswer(call -> {
            if (locks.incrementAndGet() <= 2) { both.await(10, TimeUnit.SECONDS); }
            return sqlSession.getMapper(PaymentSimulatorMapper.class).lockPayment(call.getArgument(0));
        }).when(mapper).lockPayment(anyString());
    }

    private void failAfterActualNotificationInsert() {
        doAnswer(call -> {
            int changed = sqlSession.getMapper(PaymentSimulatorMapper.class).insertNotification(call.getArgument(0));
            assertEquals(1, changed);
            assertTrue(count("simulated_notification") > 0, "fault must occur after a genuine notification INSERT");
            throw new IllegalStateException("fault after real notification insertion");
        }).when(mapper).insertNotification(any());
    }

    private void restoreNotificationInsert() {
        doAnswer(call -> sqlSession.getMapper(PaymentSimulatorMapper.class).insertNotification(call.getArgument(0)))
                .when(mapper).insertNotification(any());
    }

    private void insertReservedForLock(Binding request) {
        jdbc.update("""
                INSERT INTO simulated_payment
                  (payment_number,merchant_order_number,amount,payment_expires_at,payment_status,created_at,updated_at)
                VALUES (?,?,?,?,'RESERVED',?,?)
                """, request.paymentNumber(), request.merchantOrderNumber(), request.amount(),
                Timestamp.from(request.paymentExpiresAt()), Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private void assertBindingConflicts(Binding request) {
        for (var changed : List.of(
                new Binding(request.paymentNumber(), "different-merchant-order", request.amount(), request.paymentExpiresAt()),
                new Binding(request.paymentNumber(), request.merchantOrderNumber(), new BigDecimal("761.00"), request.paymentExpiresAt()),
                new Binding(request.paymentNumber(), request.merchantOrderNumber(), request.amount(), request.paymentExpiresAt().plusNanos(1_000)))) {
            assertConflict("SIMULATOR_BINDING_CONFLICT", () -> simulator.start(changed));
            assertConflict("SIMULATOR_BINDING_CONFLICT", () -> simulator.close(changed));
        }
    }

    private Binding binding(String number) { return binding(number, NOW.plusSeconds(900)); }
    private Binding binding(String number, Instant deadline) { return new Binding(number, "merchant-" + number, AMOUNT, deadline); }
    private Notification onlyNotice(String paymentNumber) {
        var notices = simulator.notifications(paymentNumber);
        assertEquals(1, notices.size());
        return notices.getFirst();
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private void assertNoReservedRows() {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM simulated_payment WHERE payment_status='RESERVED'", Integer.class));
    }
    private void assertConflict(String code, Runnable action) {
        var error = assertThrows(BusinessException.class, action::run);
        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals(code, error.getCode());
    }
    private void assertStatus(HttpStatus status, Runnable action) {
        assertEquals(status, assertThrows(BusinessException.class, action::run).getStatus());
    }
    private void assertSqlCode(int code, Runnable mutation) {
        var error = assertThrows(DataAccessException.class, mutation::run);
        var sqlError = assertInstanceOf(SQLException.class, error.getMostSpecificCause());
        assertEquals(code, sqlError.getErrorCode());
    }
    private TransactionTemplate transaction() {
        var transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }
    private void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
    private Outcome outcome(Callable<Payment> action) throws Exception {
        try { return new Outcome(action.call(), null); }
        catch (BusinessException error) { return new Outcome(null, error); }
    }
    private <T> List<T> compete(Callable<T> first, Callable<T> second) throws Exception {
        var start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(10, TimeUnit.SECONDS); return first.call(); });
            var b = pool.submit(() -> { start.await(10, TimeUnit.SECONDS); return second.call(); });
            return collect(List.of(a, b));
        }
    }
    private <T> List<T> collect(List<Future<T>> futures) throws Exception {
        var values = new ArrayList<T>();
        Exception firstError = null;
        for (var future : futures) {
            try { values.add(future.get(20, TimeUnit.SECONDS)); }
            catch (Exception error) {
                if (firstError == null) { firstError = error; }
                else { firstError.addSuppressed(error); }
            }
        }
        if (firstError != null) { throw firstError; }
        return List.copyOf(values);
    }
    private record Outcome(Payment payment, BusinessException error) { }

    @TestConfiguration
    static class TestClock {
        @Bean @Primary MutableClock simulatorTestClock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        private volatile Instant at = NOW;
        void set(Instant instant) { at = instant; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            if (!zone.equals(ZoneOffset.UTC)) { throw new IllegalArgumentException("Test clock uses UTC"); }
            return this;
        }
        @Override public Instant instant() { return at; }
    }
}
