package com.ticketing.trade;

import com.ticketing.catalog.EventService;
import com.ticketing.catalog.CatalogPurchaseService;
import com.ticketing.catalog.SessionManagementService;
import com.ticketing.catalog.SessionService;
import com.ticketing.common.web.BusinessException;
import com.ticketing.identity.DemoAccountSeeder;
import com.ticketing.simulator.PaymentSimulatorModels.Binding;
import com.ticketing.simulator.PaymentSimulatorModels.Payment;
import com.ticketing.simulator.PaymentSimulatorService;
import com.ticketing.support.TestAuthHelper;
import com.ticketing.support.Week2Fixtures;
import com.ticketing.venue.VenueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
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
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.ticketing.catalog.CatalogModels.SalesStatus.*;
import static com.ticketing.catalog.CatalogRequests.*;
import static com.ticketing.trade.TradeModels.*;
import static com.ticketing.trade.TradeRequests.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({PaymentTestSupport.TestClock.class, PaymentTestSupport.RealMapperSpies.class})
class PaymentLifecycleIT extends PaymentTestSupport {
    @Test
    void successAtomicallyPaysTheOrderConsumesTheHoldSellsBothSeatsAndIssuesExactlyTwoTickets() {
        var attempt = payment.start(consumer1, order.id(), "normal");
        assertEquals("PROCESSING", attempt.paymentStatus().name());
        assertEquals(order.totalAmount(), attempt.amount());
        assertEquals(order.paymentExpiresAt(), attempt.paymentExpiresAt());
        var channel = simulator.succeed(attempt.paymentNumber());
        payment.applyChannel(channel);
        assertPaid(order);
        assertEquals("SUCCEEDED", payment.payments(consumer1, order.id()).getFirst().paymentStatus().name());
        assertEquals(2, payment.tickets(consumer1, order.id()).size());
        var firstTicketIds = jdbc.queryForList("SELECT id FROM ticket WHERE order_id=? ORDER BY id", Long.class, order.id());
        payment.applyChannel(channel);
        assertEquals(firstTicketIds, jdbc.queryForList("SELECT id FROM ticket WHERE order_id=? ORDER BY id", Long.class, order.id()));
        assertEquals(attempt.id(), payment.start(consumer1, order.id(), "normal").id());
        assertEquals(1, count("payment_record"));
    }

    @Test
    void explicitFailurePreservesSeatsAndHistoryAndOnlyANewKeyCreatesTheNextAttempt() {
        var first = payment.start(consumer1, order.id(), "first");
        payment.applyChannel(simulator.fail(first.paymentNumber()));
        assertHeld(order);
        assertEquals("FAILED", payment.start(consumer1, order.id(), "first").paymentStatus().name());
        assertEquals(1, count("payment_record"));
        var second = payment.start(consumer1, order.id(), "second");
        assertNotEquals(first.id(), second.id());
        assertNotEquals(first.paymentNumber(), second.paymentNumber());
        assertEquals(order.paymentExpiresAt(), second.paymentExpiresAt());
        payment.applyChannel(simulator.succeed(second.paymentNumber()));
        assertPaid(order);
        assertEquals(List.of("FAILED", "SUCCEEDED"), payment.payments(consumer1, order.id()).stream().map(p -> p.paymentStatus().name()).toList());
    }

    @Test
    void firstPaymentUsesTheStrictOriginalDeadlineEvenWhenSeatsRemainHeld() {
        var equal = newOrder("equal-order", "FLOOR-A", "B");
        var after = newOrder("after-order", "BALCONY-B", "D");
        clock.set(order.paymentExpiresAt().minusNanos(1_000));
        var before = payment.start(consumer1, order.id(), "before");
        assertEquals("PROCESSING", before.paymentStatus().name());
        clock.set(equal.paymentExpiresAt());
        assertStatus(HttpStatus.CONFLICT, () -> payment.start(consumer1, equal.id(), "equal"));
        clock.set(after.paymentExpiresAt().plusNanos(1_000));
        assertStatus(HttpStatus.CONFLICT, () -> payment.start(consumer1, after.id(), "after"));
        assertEquals(1, count("payment_record"));
        assertHeld(equal);
        assertHeld(after);
        assertEquals(before.id(), payment.start(consumer1, order.id(), "before").id());
    }

    @Test
    void firstChannelCallCrossingTheDeadlineReturnsThePersistedRefusalAndSafelyExpiresTheOrder() {
        clock.set(order.paymentExpiresAt().minusNanos(1_000));
        var target = AopTestUtils.<PaymentSimulatorService>getUltimateTargetObject(simulator);
        doAnswer(call -> {
            clock.set(order.paymentExpiresAt());
            return call.callRealMethod();
        }).when(target).start(any());
        var attempt = payment.start(consumer1, order.id(), "crossed");
        assertEquals("FAILED", attempt.paymentStatus().name());
        assertEquals("EXPIRED_BEFORE_ACCEPTANCE", attempt.failureCode());
        assertNull(attempt.acceptedAt());
        assertCancelled(order, "EXPIRED");
        assertEquals(0, count("ticket"));
    }

    @Test
    void acceptedLateSuccessStillPaysAfterDeadlineAndSalesOrTierSwitchesDoNotInvalidateIt() {
        var attempt = payment.start(consumer1, order.id(), "late");
        management.setSalesStatus(order.sessionId(), new UpdateSalesStatus(STOPPED));
        management.setTierEnabled(order.sessionId(), order.items().getFirst().priceTierId(), new SetTierEnabled(false));
        clock.set(order.paymentExpiresAt().plusSeconds(2));
        payment.applyChannel(simulator.succeed(attempt.paymentNumber()));
        assertPaid(order);
        assertStatus(HttpStatus.CONFLICT, () -> payment.start(consumer1, order.id(), "new-after-paid"));
    }

    @Test
    void oldProcessingSnapshotAfterSuccessAndRepeatedSuccessNoticesCannotDowngradeOrDuplicateTickets() {
        var attempt = payment.start(consumer1, order.id(), "notices");
        var old = simulator.notifications(attempt.paymentNumber()).getFirst();
        simulator.succeed(attempt.paymentNumber());
        var success = simulator.notifications(attempt.paymentNumber()).stream().filter(n -> n.observedStatus().name().equals("SUCCEEDED")).findFirst().orElseThrow();
        payment.deliver(success.notificationNumber());
        payment.deliver(old.notificationNumber());
        payment.deliver(success.notificationNumber());
        payment.deliver(simulator.createNotification(attempt.paymentNumber()).notificationNumber());
        assertPaid(order);
        assertEquals("SUCCEEDED", payment.payments(consumer1, order.id()).getFirst().paymentStatus().name());
        assertEquals(2, count("ticket"));
        assertTrue(simulator.notifications(attempt.paymentNumber()).stream().allMatch(n -> n.deliveredAt() != null));
    }

    @Test
    void successCanBeAppliedWhileLocalPaymentIsStillInitiatedBeforeTheAcceptanceResponseReturns() {
        var target = AopTestUtils.<PaymentSimulatorService>getUltimateTargetObject(simulator);
        doAnswer(call -> {
            var processing = (Payment) call.callRealMethod();
            payment.applyChannel(simulator.succeed(processing.paymentNumber()));
            return processing;
        }).when(target).start(any());
        var attempt = payment.start(consumer1, order.id(), "early-success");
        assertEquals("SUCCEEDED", attempt.paymentStatus().name());
        assertPaid(order);
    }

    @Test
    void waitingForTheActualSessionLockCrossingTheDeadlineCannotCreateANewAttempt() throws Exception {
        clock.set(order.paymentExpiresAt().minusNanos(1_000));
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
            var paying = pool.submit(() -> paymentOutcome(() -> payment.start(consumer1, order.id(), "wait-expiry")));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                clock.set(order.paymentExpiresAt());
            } finally { release.countDown(); }
            var results = collect(List.of(holder, paying));
            assertEquals("RELEASED", results.getFirst());
            assertEquals("PAYMENT_EXPIRED", results.getLast());
        } finally { release.countDown(); }
        assertEquals(0, count("payment_record"));
        assertHeld(order);
    }

    @Test
    void sameKeyConcurrentRequestsCreateOneAttemptAndDifferentKeysCannotCreateTwoActivePayments() throws Exception {
        var same = compete(() -> payment.start(consumer1, order.id(), "same"), () -> payment.start(consumer1, order.id(), "same"));
        assertEquals(same.getFirst().id(), same.getLast().id());
        assertEquals(1, count("payment_record"));
        assertEquals(1, count("simulated_payment"));
        var other = newOrder("parallel-order", "FLOOR-A", "B");
        var outcomes = compete(() -> paymentOutcome(() -> payment.start(consumer1, other.id(), "left")),
                () -> paymentOutcome(() -> payment.start(consumer1, other.id(), "right")));
        assertEquals(1, outcomes.stream().filter("CREATED"::equals).count());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE order_id=? AND payment_status IN ('INITIATED','PROCESSING')", Integer.class, other.id()));
    }

    @Test
    void duplicateSuccessResultTransactionsHaveOnePaidOrderAndOneTicketPerItem() throws Exception {
        var attempt = payment.start(consumer1, order.id(), "parallel-results");
        var channel = simulator.succeed(attempt.paymentNumber());
        compete(() -> { payment.applyChannel(channel); return "APPLIED"; }, () -> { payment.applyChannel(channel); return "APPLIED"; });
        assertPaid(order);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(DISTINCT order_item_id) FROM ticket WHERE order_id=?", Integer.class, order.id()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE order_id=? AND payment_status='SUCCEEDED'", Integer.class, order.id()));
    }

    @Test
    void actualFirstTicketWriteFailureRollsBackLocalPaymentOrderInventoryAndEveryTicketButKeepsChannelSuccess() throws Exception {
        var attempt = payment.start(consumer1, order.id(), "ticket-fault");
        var channel = simulator.succeed(attempt.paymentNumber());
        failAfterFirstActualTicketInsert();
        assertThrows(IllegalStateException.class, () -> payment.applyChannel(channel));
        assertHeld(order);
        assertEquals("PROCESSING", payment.payments(consumer1, order.id()).getFirst().paymentStatus().name());
        assertEquals("SUCCEEDED", simulator.find(attempt.paymentNumber()).orElseThrow().paymentStatus().name());
        assertEquals(0, count("ticket"));
        payment.applyChannel(channel);
        assertPaid(order);
    }

    @Test
    void beforeCommitFaultRollsBackEvenAfterAllPaymentOrderSeatAndTicketWritesHaveSucceeded() {
        var attempt = payment.start(consumer1, order.id(), "before-commit");
        var channel = simulator.succeed(attempt.paymentNumber());
        var target = AopTestUtils.<PaymentTransactionService>getUltimateTargetObject(writer);
        var applications = new AtomicInteger();
        doAnswer(call -> {
            var result = call.callRealMethod();
            if (applications.incrementAndGet() == 1) {
                assertEquals(2, count("ticket"));
                assertEquals("PAID", jdbc.queryForObject("SELECT order_status FROM customer_order WHERE id=?", String.class, order.id()));
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) {
                        throw new IllegalStateException("test fault immediately before actual commit");
                    }
                });
            }
            return result;
        }).when(target).applyChannel(any());
        assertThrows(IllegalStateException.class, () -> payment.applyChannel(channel));
        assertHeld(order);
        assertEquals("PROCESSING", payment.payments(consumer1, order.id()).getFirst().paymentStatus().name());
        assertEquals(0, count("ticket"));
        assertEquals("SUCCEEDED", simulator.find(attempt.paymentNumber()).orElseThrow().paymentStatus().name());
        payment.applyChannel(channel);
        assertPaid(order);
    }

    @Test
    void failedNoticeDeliveryRemainsPendingAndTheSameNoticeCanCompleteAfterLocalRollback() throws Exception {
        var attempt = payment.start(consumer1, order.id(), "delivery-fault");
        simulator.succeed(attempt.paymentNumber());
        var success = simulator.notifications(attempt.paymentNumber()).stream().filter(n -> n.observedStatus().name().equals("SUCCEEDED")).findFirst().orElseThrow();
        failAfterFirstActualTicketInsert();
        assertThrows(IllegalStateException.class, () -> payment.deliver(success.notificationNumber()));
        assertNull(simulator.notifications(attempt.paymentNumber()).stream().filter(n -> n.notificationNumber().equals(success.notificationNumber())).findFirst().orElseThrow().deliveredAt());
        assertHeld(order);
        var failed = payment.state(order.id());
        assertEquals(1, failed.consecutiveFailures());
        assertEquals(NOW, failed.firstUnresolvedAt());
        assertEquals("LOCAL_APPLY_FAILED", failed.recentError());
        assertEquals(NOW.plusSeconds(5), failed.nextRecoveryAt());
        assertFalse(failed.manualRequired());
        assertNull(failed.recoveryClaimUntil());
        payment.deliver(success.notificationNumber());
        assertPaid(order);
        assertNotNull(simulator.notifications(attempt.paymentNumber()).stream().filter(n -> n.notificationNumber().equals(success.notificationNumber())).findFirst().orElseThrow().deliveredAt());
        var restored = payment.state(order.id());
        assertEquals(0, restored.consecutiveFailures());
        assertNull(restored.firstUnresolvedAt());
        assertNull(restored.recentError());
        assertFalse(restored.manualRequired());
    }

    @Test
    void oldProcessingNoticeCannotClearAnUnresolvedTicketFailureForTheSameSuccessfulPayment() throws Exception {
        var attempt = payment.start(consumer1, order.id(), "old-processing-after-ticket-fault");
        var processing = simulator.notifications(attempt.paymentNumber()).getFirst();
        simulator.succeed(attempt.paymentNumber());
        var success = simulator.notifications(attempt.paymentNumber()).stream()
                .filter(n -> n.observedStatus().name().equals("SUCCEEDED")).findFirst().orElseThrow();
        failAfterFirstActualTicketInsert();
        assertThrows(IllegalStateException.class, () -> payment.deliver(success.notificationNumber()));
        var failed = payment.state(order.id());
        assertEquals(1, failed.consecutiveFailures());
        assertEquals(NOW, failed.firstUnresolvedAt());
        assertEquals("LOCAL_APPLY_FAILED", failed.recentError());

        clock.set(NOW.plusSeconds(1));
        payment.deliver(processing.notificationNumber());
        var afterOldNotice = payment.state(order.id());
        assertEquals(failed.consecutiveFailures(), afterOldNotice.consecutiveFailures());
        assertEquals(failed.firstUnresolvedAt(), afterOldNotice.firstUnresolvedAt());
        assertEquals(failed.recentError(), afterOldNotice.recentError());
        assertEquals("PROCESSING", afterOldNotice.payments().getFirst().paymentStatus().name());
        assertNull(afterOldNotice.recoveryClaimUntil());
        assertHeld(order);
        assertEquals("SUCCEEDED", simulator.find(attempt.paymentNumber()).orElseThrow().paymentStatus().name());
        assertNull(simulator.notifications(attempt.paymentNumber()).stream()
                .filter(n -> n.notificationNumber().equals(success.notificationNumber())).findFirst().orElseThrow().deliveredAt());

        payment.deliver(success.notificationNumber());
        assertPaid(order);
        var restored = payment.state(order.id());
        assertEquals(0, restored.consecutiveFailures());
        assertNull(restored.firstUnresolvedAt());
        assertNull(restored.recentError());
        assertNotNull(simulator.notifications(attempt.paymentNumber()).stream()
                .filter(n -> n.notificationNumber().equals(success.notificationNumber())).findFirst().orElseThrow().deliveredAt());
    }

    @Test
    void incorrectChannelBindingCannotBeAppliedToAnotherMerchantOrderOrAmount() {
        var attempt = payment.start(consumer1, order.id(), "binding");
        var actual = simulator.succeed(attempt.paymentNumber());
        var wrongOrder = new Payment(actual.paymentNumber(), "not-this-order", actual.amount(), actual.paymentExpiresAt(),
                actual.paymentStatus(), actual.acceptedAt(), actual.resultAt(), actual.failureCode(), actual.createdAt(), actual.updatedAt());
        var wrongAmount = new Payment(actual.paymentNumber(), actual.merchantOrderNumber(), new BigDecimal("1.00"), actual.paymentExpiresAt(),
                actual.paymentStatus(), actual.acceptedAt(), actual.resultAt(), actual.failureCode(), actual.createdAt(), actual.updatedAt());
        assertStatus(HttpStatus.CONFLICT, () -> payment.applyChannel(wrongOrder));
        assertStatus(HttpStatus.CONFLICT, () -> payment.applyChannel(wrongAmount));
        assertHeld(order);
        assertEquals(0, count("ticket"));
        payment.applyChannel(actual);
        assertPaid(order);
    }

    @Test
    void personalPaymentAndTicketQueriesAndCommandsDoNotRevealAnotherCustomersOrder() throws Exception {
        assertStatus(HttpStatus.NOT_FOUND, () -> payment.start(consumer2, order.id(), "foreign"));
        assertStatus(HttpStatus.NOT_FOUND, () -> payment.payments(consumer2, order.id()));
        assertStatus(HttpStatus.NOT_FOUND, () -> payment.tickets(consumer2, order.id()));
        assertStatus(HttpStatus.NOT_FOUND, () -> payment.cancel(consumer2, order.id()));
        var operator = TestAuthHelper.login(json, port, "operator1");
        var otherConsumer = TestAuthHelper.login(json, port, "consumer2");
        assertEquals(403, http("POST", "/api/orders/" + order.id() + "/payments", operator, "wrong-role").statusCode());
        assertEquals(404, http("GET", "/api/orders/" + order.id() + "/tickets", otherConsumer, null).statusCode());
        assertEquals(401, http("POST", "/api/orders/" + order.id() + "/payments", null, "anonymous").statusCode());
        assertEquals(0, count("payment_record"));
    }

    @Test
    void mysqlRejectsDuplicateActivePaymentsAndCrossSessionPaymentAssociations() {
        var attempt = payment.start(consumer1, order.id(), "db-guard");
        assertSqlCode(1062, () -> jdbc.update("""
                INSERT INTO payment_record (order_id,session_id,payment_number,amount,payment_expires_at,request_key,
                  payment_status,created_at,updated_at)
                SELECT order_id,session_id,'duplicate-active',amount,payment_expires_at,'duplicate-key',
                  'INITIATED',created_at,updated_at FROM payment_record WHERE id=?
                """, attempt.id()));
        long otherSession = sessions.create(Week2Fixtures.request("SESSION-PAYMENT-FOREIGN", fixture)).id();
        assertSqlCode(1452, () -> jdbc.update("UPDATE payment_record SET session_id=? WHERE id=?", otherSession, attempt.id()));
        assertSqlCode(3819, () -> jdbc.update("UPDATE payment_record SET amount=0 WHERE id=?", attempt.id()));
        assertEquals(1, count("payment_record"));
        assertHeld(order);
    }

    @Test
    void mysqlRejectsTicketItemOrderOrSeatMismatchAndDuplicateItemOrOccupiedSeat() {
        var attempt = payment.start(consumer1, order.id(), "ticket-guard");
        payment.applyChannel(simulator.succeed(attempt.paymentNumber()));
        var other = newOrder("other-ticket-order", "FLOOR-A", "B");
        long ticket = jdbc.queryForObject("SELECT MIN(id) FROM ticket WHERE order_id=?", Long.class, order.id());
        assertSqlCode(1452, () -> jdbc.update("UPDATE ticket SET order_id=? WHERE id=?", other.id(), ticket));
        assertSqlCode(1452, () -> jdbc.update("UPDATE ticket SET session_seat_id=? WHERE id=?", other.items().getFirst().sessionSeatId(), ticket));
        assertSqlCode(1062, () -> jdbc.update("""
                INSERT INTO ticket(ticket_number,order_item_id,order_id,session_id,session_seat_id,ticket_status,issued_at,updated_at)
                SELECT 'duplicate-ticket',order_item_id,order_id,session_id,session_seat_id,ticket_status,issued_at,updated_at
                  FROM ticket WHERE id=?
                """, ticket));
        assertPaid(order);
    }
}

/** Shared fixture and deterministic concurrency helpers for the two payment test classes. */
abstract class PaymentTestSupport {
    static final Instant NOW = Instant.parse("2026-10-10T03:00:00.123456Z");
    @Autowired protected PaymentService payment;
    @Autowired protected OrderService orders;
    @Autowired protected SessionService sessions;
    @Autowired protected SessionManagementService management;
    @Autowired protected VenueService venues;
    @Autowired protected EventService events;
    @Autowired protected DemoAccountSeeder accounts;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected SqlSessionTemplate sqlSession;
    @Autowired protected PlatformTransactionManager transactions;
    @Autowired protected MutableClock clock;
    @Autowired protected JsonMapper json;
    @MockitoSpyBean protected PaymentSimulatorService simulator;
    @MockitoSpyBean protected PaymentTransactionService writer;
    @MockitoSpyBean protected CatalogPurchaseService purchase;
    @Autowired @Qualifier("ticketMapper") protected Object ticketMapper;
    @Autowired @Qualifier("inventoryHoldMapper") protected Object inventoryHoldMapper;
    @Value("${local.server.port}") protected int port;
    protected Week2Fixtures.Fixture fixture;
    protected long sessionId;
    protected long consumer1;
    protected long consumer2;
    protected OrderDetails order;

    @BeforeEach
    void preparePaymentFixture() {
        clock.set(NOW);
        // These interface-preserving spies are installed by our test post-processor, not MockitoSpyBean.
        reset(ticketMapper, inventoryHoldMapper);
        Week2Fixtures.resetTestDatabase(jdbc);
        accounts.seed(TestAuthHelper.PASSWORD);
        consumer1 = jdbc.queryForObject("SELECT id FROM account WHERE login_name='consumer1'", Long.class);
        consumer2 = jdbc.queryForObject("SELECT id FROM account WHERE login_name='consumer2'", Long.class);
        fixture = Week2Fixtures.create(venues, events);
        sessionId = sessions.create(Week2Fixtures.request("SESSION-PAYMENT", fixture)).id();
        management.setSalesStatus(sessionId, new UpdateSalesStatus(ON_SALE));
        order = newOrder("order", "FLOOR-A", "A");
    }

    protected OrderDetails newOrder(String key, String area, String row) {
        var selected = sessions.listSeats(sessionId).stream().filter(s -> s.areaCodeSnapshot().equals(area)
                && s.rowLabelSnapshot().equals(row) && s.seatNumberSnapshot() <= 2).map(s -> s.id()).toList();
        return orders.create(consumer1, key, new CreateOrder(sessionId, SelectionMode.RECOMMENDED, selected)).order();
    }
    protected Binding binding(PaymentModels.PaymentRecord attempt) {
        var original = orders.get(consumer1, attempt.orderId());
        return new Binding(attempt.paymentNumber(), original.orderNumber(), attempt.amount(), attempt.paymentExpiresAt());
    }
    protected void assertHeld(OrderDetails original) {
        var current = orders.get(consumer1, original.id());
        assertEquals(OrderStatus.PENDING_PAYMENT, current.orderStatus());
        assertEquals("ACTIVE", current.hold().status().name());
        assertEquals(original.items().size(), jdbc.queryForObject("SELECT COUNT(*) FROM session_seat WHERE current_hold_id=? AND seat_status='HELD'", Integer.class, original.hold().id()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ticket WHERE order_id=?", Integer.class, original.id()));
    }
    protected void assertPaid(OrderDetails original) {
        var current = orders.get(consumer1, original.id());
        assertEquals(OrderStatus.PAID, current.orderStatus());
        assertEquals("CONSUMED", current.hold().status().name());
        assertEquals(original.items().size(), jdbc.queryForObject("SELECT COUNT(*) FROM session_seat s JOIN order_item i ON i.session_seat_id=s.id WHERE i.order_id=? AND s.seat_status='SOLD' AND s.current_hold_id IS NULL", Integer.class, original.id()));
        assertEquals(original.items().size(), jdbc.queryForObject("SELECT COUNT(*) FROM ticket WHERE order_id=? AND ticket_status='VALID'", Integer.class, original.id()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE order_id=? AND payment_status='SUCCEEDED'", Integer.class, original.id()));
    }
    protected void assertCancelled(OrderDetails original, String holdStatus) {
        var current = orders.get(consumer1, original.id());
        assertEquals(OrderStatus.CANCELLED, current.orderStatus());
        assertEquals(holdStatus, current.hold().status().name());
        assertEquals(original.items().size(), jdbc.queryForObject("SELECT COUNT(*) FROM session_seat s JOIN order_item i ON i.session_seat_id=s.id WHERE i.order_id=? AND s.seat_status='AVAILABLE' AND s.current_hold_id IS NULL", Integer.class, original.id()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ticket WHERE order_id=?", Integer.class, original.id()));
    }
    protected int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    protected void assertStatus(HttpStatus status, Runnable action) {
        assertEquals(status, assertThrows(BusinessException.class, action::run).getStatus());
    }
    protected void assertSqlCode(int code, Runnable mutation) {
        var error = assertThrows(DataAccessException.class, mutation::run);
        var sql = assertInstanceOf(SQLException.class, error.getMostSpecificCause());
        assertEquals(code, sql.getErrorCode());
    }
    protected String paymentOutcome(Callable<?> call) throws Exception {
        try { call.call(); return "CREATED"; }
        catch (BusinessException error) { assertEquals(HttpStatus.CONFLICT, error.getStatus()); return error.getCode(); }
    }
    protected <T> List<T> compete(Callable<T> first, Callable<T> second) throws Exception {
        var start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(10, TimeUnit.SECONDS); return first.call(); });
            var b = pool.submit(() -> { start.await(10, TimeUnit.SECONDS); return second.call(); });
            return collect(List.of(a, b));
        }
    }
    protected <T> List<T> collect(List<Future<T>> futures) throws Exception {
        var values = new ArrayList<T>();
        Exception failure = null;
        for (var future : futures) {
            try { values.add(future.get(20, TimeUnit.SECONDS)); }
            catch (Exception error) { if (failure == null) { failure = error; } else { failure.addSuppressed(error); } }
        }
        if (failure != null) { throw failure; }
        return values;
    }
    protected void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
    protected TransactionTemplate transaction() {
        var template = new TransactionTemplate(transactions);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }
    protected HttpResponse<String> http(String method, String path, String token, String key) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
            if (token != null) { request.header("Authorization", "Bearer " + token); }
            if (key != null) { request.header("Idempotency-Key", key); }
            return client.send(request.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    protected void failAfterFirstActualTicketInsert() throws Exception {
        injectActualTicketInsertFailure(false);
    }
    protected void failAfterEveryActualTicketInsert() throws Exception {
        injectActualTicketInsertFailure(true);
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void injectActualTicketInsertFailure(boolean everyAttempt) throws Exception {
        var type = Class.forName("com.ticketing.ticketing.TicketMapper");
        var method = Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals("insertTicket")).findFirst().orElseThrow();
        method.setAccessible(true);
        var inserted = new AtomicInteger();
        var stubbed = doAnswer(call -> {
            int changed = (int) method.invoke(sqlSession.getMapper((Class) type), call.getArguments());
            if (inserted.incrementAndGet() == 1 || everyAttempt) {
                assertEquals(1, changed);
                assertEquals(1, count("ticket"), "failure must follow the first real ticket INSERT");
                throw new IllegalStateException("test fault after first actual ticket write");
            }
            return changed;
        }).when(ticketMapper);
        method.invoke(stubbed, any(), any());
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    protected void failAfterFirstActualInventoryRelease() throws Exception {
        var type = Class.forName("com.ticketing.inventory.InventoryHoldMapper");
        var method = Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals("releaseSeat")).findFirst().orElseThrow();
        method.setAccessible(true);
        var released = new AtomicInteger();
        var stubbed = doAnswer(call -> {
            int changed = (int) method.invoke(sqlSession.getMapper((Class) type), call.getArguments());
            if (released.incrementAndGet() == 1) {
                assertEquals(1, changed);
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM session_seat s JOIN order_item i ON i.session_seat_id=s.id WHERE i.order_id=? AND s.seat_status='AVAILABLE'", Integer.class, order.id()));
                throw new IllegalStateException("test fault after first actual seat release");
            }
            return changed;
        }).when(inventoryHoldMapper);
        method.invoke(stubbed, anyLong(), anyLong(), anyLong(), any());
    }
    @TestConfiguration
    static class RealMapperSpies {
        @Bean
        static BeanPostProcessor paymentFaultMapperSpies() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    String interfaceName = switch (beanName) {
                        case "ticketMapper" -> "com.ticketing.ticketing.TicketMapper";
                        case "inventoryHoldMapper" -> "com.ticketing.inventory.InventoryHoldMapper";
                        default -> null;
                    };
                    if (interfaceName == null) { return bean; }
                    try {
                        // MapperFactoryBean itself must remain intact. Spy only its genuine interface product.
                        return Class.forName(interfaceName).isInstance(bean) ? spy(bean) : bean;
                    } catch (ClassNotFoundException missingMapper) {
                        throw new IllegalStateException("Fault injection mapper is missing", missingMapper);
                    }
                }
            };
        }
    }
    @TestConfiguration
    static class TestClock {
        @Bean @Primary MutableClock paymentTestClock() { return new MutableClock(); }
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
