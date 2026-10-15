package com.ticketing.simulator;

import com.ticketing.common.web.BusinessException;
import com.ticketing.simulator.RefundSimulatorModels.Binding;
import com.ticketing.simulator.RefundSimulatorModels.Notification;
import com.ticketing.simulator.RefundSimulatorModels.Refund;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.ticketing.simulator.RefundSimulatorModels.Status.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** Independent channel transactions against MySQL; these tests do not create merchant refunds or tickets. */
@SpringBootTest
@ActiveProfiles("test")
@Import(RefundSimulatorIT.TestClock.class)
class RefundSimulatorIT {
    private static final Instant NOW = Instant.parse("2026-10-15T02:00:00.123456Z");
    private static final BigDecimal PAID = new BigDecimal("760.00");
    private static final BigDecimal HALF = new BigDecimal("380.00");
    private static final String PAYMENT = "refund-source-payment";
    private static final String ORDER = "refund-source-order";
    @Autowired private RefundSimulatorService refunds;
    @Autowired private PaymentSimulatorService payments;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SqlSessionTemplate sqlSession;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private MutableClock clock;
    @MockitoSpyBean private RefundSimulatorMapper mapper;

    @BeforeEach
    void prepare() {
        clock.set(NOW);
        Week2Fixtures.resetTestDatabase(jdbc);
        payments.start(new PaymentSimulatorModels.Binding(PAYMENT, ORDER, PAID, NOW.plusSeconds(900)));
        payments.succeed(PAYMENT);
    }

    @Test
    void acceptancePersistsTheOriginalPaymentTicketAndAmountWithAnImmutableProcessingSnapshot() {
        var request = binding("accept");
        var refund = refunds.start(request);
        assertEquals(request.refundNumber(), refund.refundNumber());
        assertEquals(PAYMENT, refund.paymentNumber());
        assertEquals(ORDER, refund.merchantOrderNumber());
        assertEquals(request.ticketNumber(), refund.ticketNumber());
        assertEquals(HALF, refund.amount());
        assertEquals(PROCESSING, refund.refundStatus());
        assertEquals(NOW, refund.acceptedAt());
        assertNull(refund.resultAt());
        assertNull(refund.failureCode());
        assertEquals(refund, refunds.find(request.refundNumber()).orElseThrow());
        var notice = onlyNotice(request.refundNumber());
        assertEquals(refund, notice.refund());
        assertEquals(NOW, notice.notifiedAt());
        assertNull(notice.deliveredAt());
        assertEquals(1, count("simulator_refund"));
        assertEquals(1, count("simulator_refund_notification"));
        for (var table : List.of("refund_record", "customer_order", "payment_record", "ticket", "account")) {
            assertEquals(0, count(table), "channel must not manufacture merchant rows in " + table);
        }
    }

    @Test
    void replayRetainsTheSameFactTimeAndNotificationAndComparesAmountByValue() {
        var request = binding("replay");
        var first = refunds.start(request);
        clock.set(NOW.plusSeconds(60));
        assertEquals(first, refunds.start(request));
        assertEquals(first, refunds.start(new Binding(request.refundNumber(), PAYMENT, ORDER,
                request.ticketNumber(), new BigDecimal("380.0"))));
        assertEquals(1, count("simulator_refund"));
        assertEquals(1, count("simulator_refund_notification"));
    }

    @Test
    void anExistingNumberCannotChangeItsOriginalPaymentOrderTicketOrAmountEvenAfterSuccess() {
        var request = binding("binding");
        refunds.start(request);
        assertBindingConflicts(request);
        var succeeded = refunds.succeed(request.refundNumber());
        assertBindingConflicts(request);
        assertEquals(succeeded, refunds.find(request.refundNumber()).orElseThrow());
        assertEquals(2, refunds.notifications(request.refundNumber()).size());
    }

    @Test
    void onlyAnExistingSuccessfulOriginalPaymentAndItsMerchantOrderCanBeRefunded() {
        assertStatus(HttpStatus.NOT_FOUND, () -> refunds.start(new Binding("missing-source", "unknown", ORDER,
                ticket("unknown"), HALF)));
        assertConflict("SIMULATOR_BINDING_CONFLICT", () -> refunds.start(new Binding("wrong-order", PAYMENT,
                "another-order", ticket("wrong-order"), HALF)));
        for (var terminal : List.of("processing", "failed", "closed")) {
            var payment = "source-" + terminal;
            var source = new PaymentSimulatorModels.Binding(payment, ORDER, PAID, NOW.plusSeconds(900));
            payments.start(source);
            if (terminal.equals("failed")) { payments.fail(payment); }
            if (terminal.equals("closed")) { payments.close(source); }
            assertConflict("SIMULATOR_STATE_CONFLICT", () -> refunds.start(new Binding("refund-" + terminal,
                    payment, ORDER, ticket(terminal), HALF)));
        }
        assertEquals(0, count("simulator_refund"));
        assertEquals(0, count("simulator_refund_notification"));
    }

    @Test
    void processingAndSucceededAmountsReserveTheOriginalPaymentTogether() {
        var a = binding("limit-a");
        var b = binding("limit-b");
        refunds.start(a);
        refunds.start(b);
        assertConflict("SIMULATOR_REFUND_LIMIT_EXCEEDED", () -> refunds.start(new Binding("over-limit", PAYMENT,
                ORDER, ticket("over-limit"), new BigDecimal("0.01"))));
        refunds.succeed(a.refundNumber());
        assertConflict("SIMULATOR_REFUND_LIMIT_EXCEEDED", () -> refunds.start(new Binding("still-over-limit", PAYMENT,
                ORDER, ticket("still-over-limit"), new BigDecimal("0.01"))));
        assertEquals(2, count("simulator_refund"));
        assertEquals(3, count("simulator_refund_notification"));
    }

    @Test
    void anExplicitFailureReleasesReservedAmountButItsNumberCannotBecomeANewRefund() {
        var request = new Binding("failure-retry", PAYMENT, ORDER, ticket("failure-retry"), PAID);
        refunds.start(request);
        var failed = refunds.fail(request.refundNumber());
        assertEquals(FAILED, failed.refundStatus());
        assertEquals("SIMULATED_FAILURE", failed.failureCode());
        clock.set(NOW.plusSeconds(10));
        assertEquals(failed, refunds.start(request));
        assertEquals(failed, refunds.fail(request.refundNumber()));
        assertConflict("SIMULATOR_STATE_CONFLICT", () -> refunds.succeed(request.refundNumber()));
        var retry = refunds.start(new Binding("failure-new-number", PAYMENT, ORDER, request.ticketNumber(), PAID));
        assertEquals(PROCESSING, retry.refundStatus());
        assertEquals(2, count("simulator_refund"));
        assertEquals(3, count("simulator_refund_notification"));
    }

    @Test
    void repeatedSuccessPreservesItsFirstResultAndCannotBeChangedToFailure() {
        var request = binding("success");
        refunds.start(request);
        clock.set(NOW.plusSeconds(1));
        var succeeded = refunds.succeed(request.refundNumber());
        assertEquals(SUCCEEDED, succeeded.refundStatus());
        assertEquals(NOW.plusSeconds(1), succeeded.resultAt());
        assertNull(succeeded.failureCode());
        clock.set(NOW.plusSeconds(2));
        assertEquals(succeeded, refunds.succeed(request.refundNumber()));
        assertEquals(succeeded, refunds.start(request));
        assertConflict("SIMULATOR_STATE_CONFLICT", () -> refunds.fail(request.refundNumber()));
        assertEquals(2, refunds.notifications(request.refundNumber()).size());
    }

    @Test
    void concurrentFirstStartsForTheSameRefundReturnOneIdentityAndOneAcceptanceSnapshot() throws Exception {
        var request = binding("same-number-race");
        var results = compete(() -> refunds.start(request), () -> refunds.start(request));
        assertEquals(results.getFirst(), results.getLast());
        assertEquals(1, count("simulator_refund"));
        assertEquals(1, count("simulator_refund_notification"));
    }

    @Test
    void concurrentRefundsCannotBothReserveMoreThanTheOriginalSuccessfulPayment() throws Exception {
        var results = compete(() -> outcome(() -> refunds.start(new Binding("amount-race-a", PAYMENT, ORDER,
                        ticket("amount-race-a"), new BigDecimal("500.00")))),
                () -> outcome(() -> refunds.start(new Binding("amount-race-b", PAYMENT, ORDER,
                        ticket("amount-race-b"), new BigDecimal("500.00")))));
        assertEquals(1, results.stream().filter(result -> result.refund() != null).count());
        var rejected = results.stream().filter(result -> result.error() != null).findFirst().orElseThrow().error();
        assertEquals("SIMULATOR_REFUND_LIMIT_EXCEEDED", rejected.getCode());
        assertEquals(HttpStatus.CONFLICT, rejected.getStatus());
        assertEquals(new BigDecimal("500.00"), jdbc.queryForObject("SELECT SUM(amount) FROM simulator_refund", BigDecimal.class));
        assertEquals(1, count("simulator_refund_notification"));
    }

    @Test
    void competingSuccessAndFailureCommitExactlyOneTerminalSnapshot() throws Exception {
        var request = binding("terminal-race");
        refunds.start(request);
        var results = compete(() -> outcome(() -> refunds.succeed(request.refundNumber())),
                () -> outcome(() -> refunds.fail(request.refundNumber())));
        assertEquals(1, results.stream().filter(result -> result.refund() != null).count());
        var rejected = results.stream().filter(result -> result.error() != null).findFirst().orElseThrow().error();
        assertEquals("SIMULATOR_STATE_CONFLICT", rejected.getCode());
        var saved = refunds.find(request.refundNumber()).orElseThrow();
        assertTrue(saved.refundStatus() == SUCCEEDED || saved.refundStatus() == FAILED);
        assertEquals(saved, results.stream().filter(result -> result.refund() != null).findFirst().orElseThrow().refund());
        assertEquals(2, refunds.notifications(request.refundNumber()).size());
    }

    @Test
    void channelAcceptanceAndSuccessSurviveTheCallingMerchantTransactionRollback() {
        var request = binding("independent");
        assertThrows(IllegalStateException.class, () -> transaction().execute(status -> {
            refunds.start(request);
            refunds.succeed(request.refundNumber());
            throw new IllegalStateException("merchant transaction failed after the independent refund commit");
        }));
        assertEquals(SUCCEEDED, refunds.find(request.refundNumber()).orElseThrow().refundStatus());
        assertEquals(2, refunds.notifications(request.refundNumber()).size());
        assertEquals(0, count("refund_record"));
        assertEquals(PaymentSimulatorModels.PaymentStatus.SUCCEEDED, payments.find(PAYMENT).orElseThrow().paymentStatus());
        assertEquals(PAID, payments.find(PAYMENT).orElseThrow().amount());
    }

    @Test
    void actualAcceptanceSnapshotInsertFailureRollsBackTheNewRefundAndCanRetryTheOriginalNumber() {
        var request = binding("acceptance-fault");
        failAfterActualNotificationInsert();
        assertThrows(IllegalStateException.class, () -> refunds.start(request));
        assertTrue(refunds.find(request.refundNumber()).isEmpty());
        assertEquals(0, count("simulator_refund"));
        assertEquals(0, count("simulator_refund_notification"));
        restoreNotificationInsert();
        assertEquals(PROCESSING, refunds.start(request).refundStatus());
        assertEquals(1, refunds.notifications(request.refundNumber()).size());
    }

    @Test
    void actualSuccessSnapshotInsertFailureRollsBackStateAndSnapshotTogether() {
        assertTerminalSnapshotRollback(true);
    }

    @Test
    void actualFailureSnapshotInsertFailureRollsBackStateAndSnapshotTogether() {
        assertTerminalSnapshotRollback(false);
    }

    @Test
    void immutableProcessingNotificationCanArriveAfterTheSuccessSnapshot() {
        var request = binding("old-notice");
        var accepted = refunds.start(request);
        var old = onlyNotice(request.refundNumber());
        clock.set(NOW.plusSeconds(1));
        var succeeded = refunds.succeed(request.refundNumber());
        var terminal = refunds.notifications(request.refundNumber()).stream()
                .filter(notice -> notice.refund().refundStatus() == SUCCEEDED).findFirst().orElseThrow();
        assertEquals(accepted, old.refund());
        assertEquals(succeeded, terminal.refund());
        var received = new ArrayList<Notification>();
        refunds.deliver(terminal.notificationNumber(), received::add);
        refunds.deliver(old.notificationNumber(), received::add);
        refunds.deliver(terminal.notificationNumber(), received::add);
        assertEquals(List.of(SUCCEEDED, PROCESSING, SUCCEEDED), received.stream().map(notice -> notice.refund().refundStatus()).toList());
        assertEquals(succeeded, refunds.find(request.refundNumber()).orElseThrow());
        var savedOld = refunds.notifications(request.refundNumber()).stream()
                .filter(notice -> notice.notificationNumber().equals(old.notificationNumber())).findFirst().orElseThrow();
        assertEquals(accepted, savedOld.refund());
        assertEquals(NOW, savedOld.notifiedAt());
        assertEquals(2, count("simulator_refund_notification"));
    }

    @Test
    void failedReceiverDoesNotAcknowledgeAndReplayPreservesTheFirstDeliveryTime() {
        var request = binding("delivery-retry");
        refunds.start(request);
        var notice = onlyNotice(request.refundNumber());
        clock.set(NOW.plusSeconds(1));
        assertThrows(IllegalStateException.class, () -> refunds.deliver(notice.notificationNumber(), value -> {
            assertEquals(notice, value);
            throw new IllegalStateException("merchant result transaction failed");
        }));
        assertEquals(notice, onlyNotice(request.refundNumber()));
        clock.set(NOW.plusSeconds(2));
        var received = new ArrayList<Notification>();
        refunds.deliver(notice.notificationNumber(), received::add);
        var delivered = onlyNotice(request.refundNumber());
        assertEquals(NOW.plusSeconds(2), delivered.deliveredAt());
        clock.set(NOW.plusSeconds(3));
        refunds.deliver(notice.notificationNumber(), received::add);
        assertEquals(2, received.size());
        assertEquals(delivered, onlyNotice(request.refundNumber()));
    }

    @Test
    void failureAfterTheActualAcknowledgementUpdateKeepsTheOriginalNotificationPending() {
        var request = binding("acknowledgement-fault");
        refunds.start(request);
        var notice = onlyNotice(request.refundNumber());
        var received = new ArrayList<Notification>();
        doAnswer(call -> {
            int changed = sqlSession.getMapper(RefundSimulatorMapper.class)
                    .acknowledgeNotification(call.getArgument(0), call.getArgument(1));
            assertEquals(1, changed);
            throw new IllegalStateException("acknowledgement transaction failed after its actual UPDATE");
        }).when(mapper).acknowledgeNotification(any(), any());
        assertThrows(IllegalStateException.class, () -> refunds.deliver(notice.notificationNumber(), received::add));
        assertEquals(1, received.size(), "the merchant receiver already returned before acknowledgement failed");
        assertEquals(notice, onlyNotice(request.refundNumber()));
        doAnswer(call -> sqlSession.getMapper(RefundSimulatorMapper.class)
                .acknowledgeNotification(call.getArgument(0), call.getArgument(1)))
                .when(mapper).acknowledgeNotification(any(), any());
        clock.set(NOW.plusSeconds(2));
        refunds.deliver(notice.notificationNumber(), received::add);
        assertEquals(2, received.size(), "merchant results must tolerate this genuine redelivery window");
        assertEquals(NOW.plusSeconds(2), onlyNotice(request.refundNumber()).deliveredAt());
        assertEquals(1, count("simulator_refund_notification"));
    }

    @Test
    void receiverRunsWithoutAChannelTransactionOrPaymentLock() {
        var request = binding("receiver-boundary");
        refunds.start(request);
        var notice = onlyNotice(request.refundNumber());
        refunds.deliver(notice.notificationNumber(), value -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(PROCESSING, refunds.find(request.refundNumber()).orElseThrow().refundStatus());
            assertEquals(PaymentSimulatorModels.PaymentStatus.SUCCEEDED, payments.find(PAYMENT).orElseThrow().paymentStatus());
        });
        assertEquals(NOW, onlyNotice(request.refundNumber()).deliveredAt());
    }

    @Test
    void unknownCommandsAndInvalidRequestsDoNotCreateRefundFacts() {
        assertTrue(refunds.find("missing").isEmpty());
        assertStatus(HttpStatus.NOT_FOUND, () -> refunds.succeed("missing"));
        assertStatus(HttpStatus.NOT_FOUND, () -> refunds.fail("missing"));
        assertStatus(HttpStatus.NOT_FOUND, () -> refunds.notifications("missing"));
        assertStatus(HttpStatus.NOT_FOUND, () -> refunds.deliver("missing", value -> fail("unexpected delivery")));
        for (var amount : List.of(BigDecimal.ZERO, new BigDecimal("-1.00"), new BigDecimal("1.001"), new BigDecimal("10000000000.00"))) {
            assertStatus(HttpStatus.BAD_REQUEST, () -> refunds.start(new Binding("invalid", PAYMENT, ORDER, ticket("invalid"), amount)));
        }
        for (var number : List.of("", "has whitespace", "非ASCII", "x".repeat(37))) {
            assertStatus(HttpStatus.BAD_REQUEST, () -> refunds.start(new Binding(number, PAYMENT, ORDER, ticket("invalid"), HALF)));
            assertStatus(HttpStatus.BAD_REQUEST, () -> refunds.start(new Binding("invalid", number, ORDER, ticket("invalid"), HALF)));
            assertStatus(HttpStatus.BAD_REQUEST, () -> refunds.start(new Binding("invalid", PAYMENT, number, ticket("invalid"), HALF)));
            assertStatus(HttpStatus.BAD_REQUEST, () -> refunds.start(new Binding("invalid", PAYMENT, ORDER, number, HALF)));
        }
        assertEquals(0, count("simulator_refund"));
        assertEquals(0, count("simulator_refund_notification"));
    }

    private void assertTerminalSnapshotRollback(boolean success) {
        var request = binding(success ? "success-fault" : "failure-fault");
        var original = refunds.start(request);
        failAfterActualNotificationInsert();
        assertThrows(IllegalStateException.class, () -> {
            if (success) { refunds.succeed(request.refundNumber()); }
            else { refunds.fail(request.refundNumber()); }
        });
        assertEquals(original, refunds.find(request.refundNumber()).orElseThrow());
        assertEquals(original, onlyNotice(request.refundNumber()).refund());
        restoreNotificationInsert();
        assertEquals(success ? SUCCEEDED : FAILED, success ? refunds.succeed(request.refundNumber()).refundStatus()
                : refunds.fail(request.refundNumber()).refundStatus());
        assertEquals(2, refunds.notifications(request.refundNumber()).size());
    }

    private void failAfterActualNotificationInsert() {
        doAnswer(call -> {
            int changed = sqlSession.getMapper(RefundSimulatorMapper.class).insertNotification(call.getArgument(0));
            assertEquals(1, changed);
            assertTrue(count("simulator_refund_notification") > 0, "fault occurs after a genuine notification INSERT");
            throw new IllegalStateException("fault after actual refund notification insertion");
        }).when(mapper).insertNotification(any());
    }

    private void restoreNotificationInsert() {
        doAnswer(call -> sqlSession.getMapper(RefundSimulatorMapper.class).insertNotification(call.getArgument(0)))
                .when(mapper).insertNotification(any());
    }

    private void assertBindingConflicts(Binding request) {
        for (var changed : List.of(
                new Binding(request.refundNumber(), "different-payment", ORDER, request.ticketNumber(), HALF),
                new Binding(request.refundNumber(), PAYMENT, "different-order", request.ticketNumber(), HALF),
                new Binding(request.refundNumber(), PAYMENT, ORDER, ticket("different-ticket"), HALF),
                new Binding(request.refundNumber(), PAYMENT, ORDER, request.ticketNumber(), new BigDecimal("381.00")))) {
            assertConflict("SIMULATOR_BINDING_CONFLICT", () -> refunds.start(changed));
        }
    }
    private Binding binding(String number) { return new Binding(number, PAYMENT, ORDER, ticket(number), HALF); }
    private String ticket(String number) { return UUID.nameUUIDFromBytes(number.getBytes(StandardCharsets.UTF_8)).toString(); }
    private Notification onlyNotice(String number) {
        var notices = refunds.notifications(number);
        assertEquals(1, notices.size());
        return notices.getFirst();
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private void assertConflict(String code, Runnable action) {
        var error = assertThrows(BusinessException.class, action::run);
        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals(code, error.getCode());
    }
    private void assertStatus(HttpStatus status, Runnable action) {
        assertEquals(status, assertThrows(BusinessException.class, action::run).getStatus());
    }
    private TransactionTemplate transaction() {
        var transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }
    private Outcome outcome(Callable<Refund> action) throws Exception {
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
    private record Outcome(Refund refund, BusinessException error) { }

    @TestConfiguration
    static class TestClock {
        @Bean @Primary MutableClock refundSimulatorTestClock() { return new MutableClock(); }
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
