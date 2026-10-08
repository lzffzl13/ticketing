package com.ticketing.trade;

import com.ticketing.catalog.CatalogPurchaseService;
import com.ticketing.catalog.EventService;
import com.ticketing.catalog.SeatQueryService;
import com.ticketing.catalog.SessionManagementService;
import com.ticketing.catalog.SessionService;
import com.ticketing.common.web.BusinessException;
import com.ticketing.identity.DemoAccountSeeder;
import com.ticketing.inventory.InventoryModels.SessionSeat;
import com.ticketing.inventory.InventoryModels.SeatStatus;
import com.ticketing.inventory.InventoryService;
import com.ticketing.support.TestAuthHelper;
import com.ticketing.support.Week2Fixtures;
import com.ticketing.venue.VenueService;
import com.ticketing.venue.VenueRequests.CreateArea;
import com.ticketing.venue.VenueRequests.CreateVenue;
import com.ticketing.venue.VenueRequests.SeatInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.ticketing.catalog.CatalogModels.SalesStatus.*;
import static com.ticketing.catalog.CatalogRequests.*;
import static com.ticketing.trade.TradeModels.*;
import static com.ticketing.trade.TradeRequests.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

/** Real MySQL transactions and commits; barriers establish ordering without timing guesses. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(OrderHoldIT.TestClock.class)
class OrderHoldIT {
    private static final Instant NOW = Instant.parse("2026-10-08T02:00:00.123456Z");
    @Autowired private OrderService orders;
    @Autowired private SessionService sessions;
    @Autowired private SeatQueryService queries;
    @Autowired private VenueService venues;
    @Autowired private EventService events;
    @Autowired private DemoAccountSeeder accounts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private JsonMapper json;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private SqlSessionTemplate sqlSession;
    @MockitoSpyBean private TradeMapper mapper;
    @MockitoSpyBean private OrderTransactionService writer;
    @MockitoSpyBean private CatalogPurchaseService purchase;
    @MockitoSpyBean private SessionManagementService management;
    @MockitoSpyBean private InventoryService inventory;
    @Value("${local.server.port}") private int port;
    private Week2Fixtures.Fixture fixture;
    private long sessionId;
    private long consumer1;
    private long consumer2;

    @BeforeEach
    void prepare() {
        clock.set(NOW);
        Week2Fixtures.resetTestDatabase(jdbc);
        accounts.seed(TestAuthHelper.PASSWORD);
        consumer1 = account("consumer1");
        consumer2 = account("consumer2");
        fixture = Week2Fixtures.create(venues, events);
        sessionId = sessions.create(Week2Fixtures.request("SESSION-W4", fixture)).id();
        open(sessionId);
    }

    @Test
    void normalOrderSnapshotsPricesAndNamesAndAtomicallyHoldsTheWholeGroupFor15Minutes() {
        var ids = ids(sessionId, "FLOOR-A", "A", 1, 2);
        var result = orders.create(consumer1, "normal", request(sessionId, SelectionMode.RECOMMENDED, ids));
        assertTrue(result.created());
        var order = result.order();
        assertEquals(consumer1, order.customerId());
        assertEquals(sessionId, order.sessionId());
        assertEquals("PENDING_PAYMENT", order.orderStatus().name());
        assertEquals("ACTIVE", order.hold().status().name());
        assertEquals(new BigDecimal("760.00"), order.totalAmount());
        assertEquals(NOW.plusSeconds(900), order.paymentExpiresAt());
        assertEquals(order.paymentExpiresAt(), order.hold().expiresAt());
        assertEquals(2, order.items().size());
        var item = order.items().getFirst();
        assertEquals("FLOOR-A", item.areaCodeSnapshot());
        assertEquals("池座", item.areaNameSnapshot());
        assertEquals("A", item.rowLabelSnapshot());
        assertEquals("A 档", item.priceTierNameSnapshot());
        assertEquals(new BigDecimal("380.00"), item.transactionPrice());
        assertEquals(order, orders.get(consumer1, order.id()));
        assertEquals(1, count("customer_order"));
        assertEquals(1, count("seat_hold"));
        assertEquals(2, count("order_item"));
        assertEquals(2, count("seat_hold_item"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM session_seat WHERE current_hold_id=? AND seat_status='HELD'",
                Integer.class, order.hold().id()));
        assertTradeInvariants();
    }

    @Test
    void manualSelectionAcrossRowsAndAisleSucceedsWhileRecommendedRequiresAdjacency() {
        var cross = List.of(seat(sessionId, "FLOOR-A", "A", 6).id(), seat(sessionId, "FLOOR-A", "B", 7).id());
        // Both selected seats lie at segment ends, so no new orphan is created.
        assertCode("NOT_ADJACENT", () -> orders.create(consumer1, "recommend-cross", request(sessionId, SelectionMode.RECOMMENDED, cross)));
        assertNoTrades();
        assertTrue(orders.create(consumer1, "manual-cross", request(sessionId, SelectionMode.MANUAL, cross)).created());
        assertTradeInvariants();
    }

    @Test
    void rejectsNewOrphansDisabledTierStoppedSessionDuplicateAndForeignSeatsWithoutPartialWrites() {
        assertCode("NEW_ORPHAN", () -> orders.create(consumer1, "orphan", request(sessionId, SelectionMode.MANUAL,
                ids(sessionId, "FLOOR-A", "A", 2, 3))));
        long other = sessions.create(Week2Fixtures.request("SESSION-W4-FOREIGN", fixture)).id();
        long own = seat(sessionId, "FLOOR-A", "A", 1).id();
        long foreign = seat(other, "FLOOR-A", "A", 1).id();
        assertCode("INVALID_SELECTION", () -> orders.create(consumer1, "duplicate", request(sessionId, SelectionMode.MANUAL, List.of(own, own))));
        assertCode("INVALID_SELECTION", () -> orders.create(consumer1, "foreign", request(sessionId, SelectionMode.MANUAL, List.of(own, foreign))));
        long tier = seat(sessionId, "FLOOR-A", "A", 1).priceTierId();
        management.setTierEnabled(sessionId, tier, new SetTierEnabled(false));
        assertCode("PRICE_TIER_DISABLED", () -> orders.create(consumer1, "disabled", request(sessionId, SelectionMode.MANUAL,
                ids(sessionId, "FLOOR-A", "A", 1, 2))));
        management.setTierEnabled(sessionId, tier, new SetTierEnabled(true));
        management.setSalesStatus(sessionId, new UpdateSalesStatus(STOPPED));
        assertCode("SESSION_NOT_ON_SALE", () -> orders.create(consumer1, "stopped", request(sessionId, SelectionMode.MANUAL,
                ids(sessionId, "FLOOR-A", "A", 1, 2))));
        assertNoTrades();
    }

    @Test
    void staleRecommendationRejectsTheEntireSelectionAndLeavesOtherSeatsAvailable() {
        var candidate = queries.recommend(sessionId, 2, "FLOOR-A", null, 5).candidates().getFirst();
        var selected = candidate.seats().stream().map(SessionSeat::id).toList();
        orders.create(consumer1, "winner", request(sessionId, SelectionMode.RECOMMENDED, selected));
        var untouched = seat(sessionId, "FLOOR-A", "B", 1);
        assertCode("SEAT_NOT_AVAILABLE", () -> orders.create(consumer2, "stale", request(sessionId,
                SelectionMode.MANUAL, List.of(selected.getFirst(), untouched.id()))));
        assertEquals(SeatStatus.AVAILABLE, seat(sessionId, "FLOOR-A", "B", 1).seatStatus());
        assertEquals(1, count("customer_order"));
        assertTradeInvariants();
    }

    @Test
    void overlappingRealTransactionsHaveOneWinnerAndNoPartialLoser() throws Exception {
        var outcomes = compete(() -> createOutcome(consumer1, "overlap-1", sessionId, ids(sessionId, "FLOOR-A", "A", 1, 2)),
                () -> createOutcome(consumer2, "overlap-2", sessionId, ids(sessionId, "FLOOR-A", "A", 1, 2, 3)));
        assertEquals(1, outcomes.stream().filter("CREATED"::equals).count());
        assertEquals(1, outcomes.stream().filter("SEAT_NOT_AVAILABLE"::equals).count());
        assertEquals(1, count("customer_order"));
        assertTradeInvariants();
    }

    @Test
    void disjointChoicesOnFiveSeatSegmentRecheckOrphansAfterTheFirstCommit() throws Exception {
        long five = fiveSeatSession();
        var outcomes = compete(() -> createOutcome(consumer1, "left", five, ids(five, "FIVE", "A", 1, 2)),
                () -> createOutcome(consumer2, "right", five, ids(five, "FIVE", "A", 4, 5)));
        assertEquals(1, outcomes.stream().filter("CREATED"::equals).count());
        assertEquals(1, outcomes.stream().filter("NEW_ORPHAN"::equals).count());
        assertEquals(3, sessions.listSeats(five).stream().filter(s -> s.seatStatus() == SeatStatus.AVAILABLE).count());
        assertEquals(1, count("customer_order"));
        assertTradeInvariants();
    }

    @Test
    void sameKeyParallelRequestsAndReorderedSeatIdsReturnExactlyOneOrder() throws Exception {
        var first = ids(sessionId, "FLOOR-A", "A", 1, 2);
        var reverse = first.reversed();
        var results = compete(() -> orders.create(consumer1, "same", request(sessionId, SelectionMode.RECOMMENDED, first)),
                () -> orders.create(consumer1, "same", request(sessionId, SelectionMode.RECOMMENDED, reverse)));
        assertEquals(results.getFirst().order().id(), results.getLast().order().id());
        assertEquals(1, results.stream().filter(CreationResult::created).count());
        assertEquals(1, count("customer_order"));
        assertEquals(2, count("order_item"));
        assertTradeInvariants();
    }

    @Test
    void sameConsumerKeyAcrossDifferentSessionsIsArbitratedByTheDatabaseUniqueConstraint() throws Exception {
        long other = sessions.create(Week2Fixtures.request("SESSION-W4-CROSSKEY", fixture)).id();
        open(other);
        // Both writers have read no matching order, locked different sessions, and passed all validation.
        // Release them immediately before genuine INSERTs to force unique-key arbitration and recovery.
        var bothReadyToInsert = new CyclicBarrier(2);
        var insertAttempts = new AtomicInteger();
        doAnswer(call -> {
            insertAttempts.incrementAndGet();
            bothReadyToInsert.await(10, TimeUnit.SECONDS);
            return sqlSession.getMapper(TradeMapper.class).insertOrder(call.getArgument(0), call.getArgument(1));
        }).when(mapper).insertOrder(any(), any());
        var outcomes = compete(() -> createOutcome(consumer1, "global-key", sessionId, ids(sessionId, "FLOOR-A", "A", 1, 2)),
                () -> createOutcome(consumer1, "global-key", other, ids(other, "FLOOR-A", "A", 1, 2)));
        assertEquals(1, outcomes.stream().filter("CREATED"::equals).count());
        assertEquals(1, outcomes.stream().filter("IDEMPOTENCY_CONFLICT"::equals).count());
        assertEquals(2, insertAttempts.get());
        assertEquals(1, count("customer_order"));
        assertEquals(1, count("seat_hold"));
        assertEquals(2, count("order_item"));
        assertEquals(2, count("seat_hold_item"));
        assertTradeInvariants();
    }

    @Test
    void keyScopeIsConsumerAndKeyCaseIsExactWhileChangedPurchaseConflicts() {
        var original = request(sessionId, SelectionMode.RECOMMENDED, ids(sessionId, "FLOOR-A", "A", 1, 2));
        orders.create(consumer1, "Same-Key", original);
        assertCode("IDEMPOTENCY_CONFLICT", () -> orders.create(consumer1, "Same-Key",
                request(sessionId, SelectionMode.MANUAL, original.seatIds())));
        assertCode("IDEMPOTENCY_CONFLICT", () -> orders.create(consumer1, "Same-Key",
                request(sessionId, SelectionMode.RECOMMENDED, ids(sessionId, "FLOOR-A", "B", 1, 2))));
        assertTrue(orders.create(consumer1, "same-key", request(sessionId, SelectionMode.RECOMMENDED,
                ids(sessionId, "FLOOR-A", "B", 1, 2))).created());
        assertTrue(orders.create(consumer2, "Same-Key", request(sessionId, SelectionMode.RECOMMENDED,
                ids(sessionId, "FLOOR-A", "A", 7, 8))).created());
        assertEquals(3, count("customer_order"));
        assertTradeInvariants();
    }

    @Test
    void failedRequestDoesNotBindItsKeyAndLaterValidRequestCanUseIt() {
        assertCode("NEW_ORPHAN", () -> orders.create(consumer1, "retry-failed", request(sessionId, SelectionMode.MANUAL,
                ids(sessionId, "FLOOR-A", "A", 2, 3))));
        assertNoTrades();
        assertTrue(orders.create(consumer1, "retry-failed", request(sessionId, SelectionMode.RECOMMENDED,
                ids(sessionId, "FLOOR-A", "A", 1, 2))).created());
        assertTradeInvariants();
    }

    @Test
    void replayAfterPauseAndExpiryReturnsOriginalDeadlineWithoutRenewingOrReleasingSeats() {
        var req = request(sessionId, SelectionMode.RECOMMENDED, ids(sessionId, "FLOOR-A", "A", 1, 2));
        var original = orders.create(consumer1, "lost-response", req).order();
        management.setSalesStatus(sessionId, new UpdateSalesStatus(STOPPED));
        clock.set(Week2Fixtures.START.plusSeconds(3600));
        var replay = orders.create(consumer1, "lost-response", req);
        assertFalse(replay.created());
        assertEquals(original, replay.order());
        assertEquals(NOW.plusSeconds(900), replay.order().paymentExpiresAt());
        assertEquals("ACTIVE", replay.order().hold().status().name());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM session_seat WHERE seat_status='HELD'", Integer.class));
        assertTradeInvariants();
    }

    @Test
    void completePaymentWindowAllowsBeforeAndExactBoundaryAndRejectsOneMicrosecondAfter() {
        Instant cutoff = sessions.get(sessionId).admissionCloseAt().minusSeconds(900);
        clock.set(cutoff.minusNanos(1_000));
        var before = orders.create(consumer1, "before", request(sessionId, SelectionMode.RECOMMENDED,
                ids(sessionId, "FLOOR-A", "A", 1, 2))).order();
        assertEquals(sessions.get(sessionId).admissionCloseAt().minusNanos(1_000), before.paymentExpiresAt());
        clock.set(cutoff);
        var exact = orders.create(consumer1, "exact", request(sessionId, SelectionMode.RECOMMENDED,
                ids(sessionId, "FLOOR-A", "B", 1, 2))).order();
        assertEquals(sessions.get(sessionId).admissionCloseAt(), exact.paymentExpiresAt());
        clock.set(cutoff.plusNanos(1_000));
        assertCode("ORDER_SALES_CLOSED", () -> orders.create(consumer1, "after", request(sessionId, SelectionMode.RECOMMENDED,
                ids(sessionId, "FLOOR-A", "A", 7, 8))));
        clock.set(Week2Fixtures.START.plusSeconds(3600));
        assertCode("ORDER_SALES_CLOSED", () -> orders.create(consumer1, "expired", request(sessionId, SelectionMode.RECOMMENDED,
                ids(sessionId, "FLOOR-A", "B", 7, 8))));
        assertEquals(2, count("customer_order"));
        assertTradeInvariants();
    }

    @Test
    void timeIsSampledAfterWaitingForSessionLockAndCrossingCutoffRejectsTheOrder() throws Exception {
        clock.set(sessions.get(sessionId).admissionCloseAt().minusSeconds(900).minusNanos(1_000));
        var holding = new CountDownLatch(1);
        var waiterEntered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var target = AopTestUtils.<CatalogPurchaseService>getUltimateTargetObject(purchase);
        doAnswer(call -> {
            waiterEntered.countDown();
            return call.callRealMethod();
        }).when(target).lockForPurchase(sessionId);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var holder = pool.submit(() -> transaction().executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM session WHERE id=? FOR UPDATE", Long.class, sessionId);
                holding.countDown();
                await(release);
            }));
            assertTrue(holding.await(10, TimeUnit.SECONDS));
            var waiter = pool.submit(() -> createOutcome(consumer1, "wait-cutoff", sessionId,
                    ids(sessionId, "FLOOR-A", "A", 1, 2)));
            try {
                assertTrue(waiterEntered.await(10, TimeUnit.SECONDS));
                clock.set(sessions.get(sessionId).admissionCloseAt().minusSeconds(900).plusNanos(1_000));
            } finally { release.countDown(); }
            holder.get(15, TimeUnit.SECONDS);
            assertEquals("ORDER_SALES_CLOSED", waiter.get(15, TimeUnit.SECONDS));
        } finally { release.countDown(); }
        assertNoTrades();
    }

    @Test
    void pauseCommittedFirstMakesWaitingOrderRejectLatestStatus() throws Exception {
        assertManagementFirst(() -> management.setSalesStatus(sessionId, new UpdateSalesStatus(STOPPED)), "SESSION_NOT_ON_SALE");
        assertNoTrades();
    }

    @Test
    void tierDisableCommittedFirstMakesWaitingOrderRejectLatestTier() throws Exception {
        long tier = seat(sessionId, "FLOOR-A", "A", 1).priceTierId();
        assertManagementFirst(() -> management.setTierEnabled(sessionId, tier, new SetTierEnabled(false)), "PRICE_TIER_DISABLED");
        assertNoTrades();
    }

    @Test
    void orderCommittedBeforePauseKeepsItsOriginalHoldAndSnapshot() throws Exception {
        assertOrderFirst(() -> management.setSalesStatus(sessionId, new UpdateSalesStatus(STOPPED)), false);
        assertEquals(STOPPED, sessions.get(sessionId).salesStatus());
        assertEquals(1, count("customer_order"));
        assertTradeInvariants();
    }

    @Test
    void orderCommittedBeforeTierDisableKeepsItsOriginalHoldAndSnapshot() throws Exception {
        long tier = seat(sessionId, "FLOOR-A", "A", 1).priceTierId();
        assertOrderFirst(() -> management.setTierEnabled(sessionId, tier, new SetTierEnabled(false)), true);
        assertFalse(sessions.listTiers(sessionId).stream().filter(t -> t.id() == tier).findFirst().orElseThrow().enabled());
        assertEquals(1, count("customer_order"));
        assertTradeInvariants();
    }

    @Test
    void faultAfterActualFirstItemWriteRollsBackOrderHoldItemsAndAllHeldSeats() {
        var writes = new AtomicInteger();
        doAnswer(call -> {
            // Interface mapper spies delegate to the genuine MyBatis mapper on the same transaction.
            int changed = sqlSession.getMapper(TradeMapper.class).insertItem(call.getArgument(0), call.getArgument(1),
                    call.getArgument(2), call.getArgument(3), call.getArgument(4));
            assertEquals(1, changed);
            assertEquals(1, count("order_item"));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM session_seat WHERE seat_status='HELD'", Integer.class));
            writes.incrementAndGet();
            throw new IllegalStateException("test fault after actual item and whole hold writes");
        }).when(mapper).insertItem(anyLong(), anyLong(), any(), any(), any());
        assertThrows(IllegalStateException.class, () -> orders.create(consumer1, "fault", request(sessionId,
                SelectionMode.RECOMMENDED, ids(sessionId, "FLOOR-A", "A", 1, 2))));
        assertEquals(1, writes.get());
        assertNoTrades();
    }

    @Test
    void faultJustBeforeCommitRollsBackTheCompleteTransaction() {
        var req = request(sessionId, SelectionMode.RECOMMENDED, ids(sessionId, "FLOOR-A", "A", 1, 2));
        var target = AopTestUtils.<OrderTransactionService>getUltimateTargetObject(writer);
        var faultOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(call -> {
            var result = call.callRealMethod();
            assertEquals(1, count("customer_order"));
            assertEquals(2, count("order_item"));
            assertTradeInvariants();
            if (faultOnce.getAndSet(false)) { throw new IllegalStateException("test fault before writer commit"); }
            return result;
        }).when(target).create(anyLong(), anyString(), any(), anyString());
        assertThrows(IllegalStateException.class, () -> orders.create(consumer1, "precommit", req));
        assertNoTrades();
        assertTrue(orders.create(consumer1, "precommit", req).created());
        assertTradeInvariants();
    }

    @Test
    void realHttpCreationReplayOwnershipAndValidationUseRealConsumerLogin() throws Exception {
        String token1 = TestAuthHelper.login(json, port, "consumer1");
        String token2 = TestAuthHelper.login(json, port, "consumer2");
        var ids = ids(sessionId, "FLOOR-A", "A", 1, 2);
        String body = """
                {"sessionId":%d,"selectionMode":"RECOMMENDED","seatIds":[%d,%d]}
                """.formatted(sessionId, ids.getFirst(), ids.getLast());
        var created = send("POST", "/api/orders", body, token1, "http-key");
        var order = data(created, 201);
        assertEquals("PENDING_PAYMENT", order.path("orderStatus").asString());
        assertEquals(2, order.path("items").size());
        assertEquals(order, data(send("POST", "/api/orders", body, token1, "http-key"), 200));
        long id = order.path("id").asLong();
        assertEquals(order, data(send("GET", "/api/orders/" + id, null, token1, null), 200));
        var foreign = send("GET", "/api/orders/" + id, null, token2, null);
        assertEquals(404, foreign.statusCode());
        assertEquals("NOT_FOUND", json.readTree(foreign.body()).path("code").asString());
        assertEquals(404, send("GET", "/api/orders/9223372036854775807", null, token1, null).statusCode());
        assertEquals(401, send("POST", "/api/orders", body, null, "anonymous").statusCode());
        assertEquals(400, send("POST", "/api/orders", body, token1, null).statusCode());
        assertEquals(400, send("POST", "/api/orders", body, token1, "invalid key").statusCode());
        assertEquals(400, send("POST", "/api/orders", body.replace("RECOMMENDED", "UNKNOWN"), token1, "invalid-mode").statusCode());
        assertEquals(400, send("POST", "/api/orders", body.replace("[%d,%d]".formatted(ids.getFirst(), ids.getLast()), "[]"),
                token1, "empty").statusCode());
        String operator = TestAuthHelper.login(json, port, "operator1");
        assertEquals(403, send("POST", "/api/orders", body, operator, "operator").statusCode());
        assertEquals(1, count("customer_order"));
        assertTradeInvariants();
    }

    private void assertManagementFirst(Callable<?> change, String expected) throws Exception {
        var written = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var target = AopTestUtils.<CatalogPurchaseService>getUltimateTargetObject(purchase);
        doAnswer(call -> { entered.countDown(); return call.callRealMethod(); }).when(target).lockForPurchase(sessionId);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> transaction().execute(status -> {
                Object result = invoke(change);
                written.countDown();
                await(release);
                return result;
            }));
            assertTrue(written.await(10, TimeUnit.SECONDS));
            var second = pool.submit(() -> createOutcome(consumer1, "management-first", sessionId,
                    ids(sessionId, "FLOOR-A", "A", 1, 2)));
            try { assertTrue(entered.await(10, TimeUnit.SECONDS)); }
            finally { release.countDown(); }
            first.get(15, TimeUnit.SECONDS);
            assertEquals(expected, second.get(15, TimeUnit.SECONDS));
        } finally { release.countDown(); }
    }

    private void assertOrderFirst(Callable<?> change, boolean tierChange) throws Exception {
        var written = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var target = AopTestUtils.<SessionManagementService>getUltimateTargetObject(management);
        var writerTarget = AopTestUtils.<OrderTransactionService>getUltimateTargetObject(writer);
        doAnswer(call -> {
            var result = call.callRealMethod();
            written.countDown();
            await(release);
            return result;
        }).when(writerTarget).create(anyLong(), anyString(), any(), anyString());
        if (tierChange) {
            doAnswer(call -> { entered.countDown(); return call.callRealMethod(); })
                    .when(target).setTierEnabled(anyLong(), anyLong(), any());
        } else {
            doAnswer(call -> { entered.countDown(); return call.callRealMethod(); })
                    .when(target).setSalesStatus(anyLong(), any());
        }
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> orders.create(consumer1, "order-first", request(sessionId, SelectionMode.RECOMMENDED,
                    ids(sessionId, "FLOOR-A", "A", 1, 2))).order());
            assertTrue(written.await(10, TimeUnit.SECONDS));
            var second = pool.submit(change);
            try { assertTrue(entered.await(10, TimeUnit.SECONDS)); }
            finally { release.countDown(); }
            var order = first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
            assertEquals(order, orders.get(consumer1, order.id()));
        } finally { release.countDown(); }
    }

    private long fiveSeatSession() {
        var venue = venues.createVenue(new CreateVenue("VENUE-FIVE", "五座同段测试", null, true));
        var area = venues.createArea(venue.id(), new CreateArea("FIVE", "五座同段", 0));
        var seats = new ArrayList<SeatInput>();
        for (int number = 1; number <= 5; number++) {
            seats.add(new SeatInput("A", 0, number, BigDecimal.valueOf(number), BigDecimal.ZERO, "ONE", true));
        }
        venues.addSeats(area.id(), seats);
        long id = sessions.create(new CreateSession("SESSION-FIVE", fixture.event().id(), venue.id(),
                Week2Fixtures.START, Week2Fixtures.START.minusSeconds(3600), Week2Fixtures.START.minusSeconds(300), true,
                List.of(new PriceTierInput("ONE", "唯一票档", new BigDecimal("100.00"))),
                List.of(new AreaPriceMapping(area.id(), "ONE")), List.of())).id();
        open(id);
        return id;
    }

    private String createOutcome(long customer, String key, long id, List<Long> ids) {
        try { return orders.create(customer, key, request(id, SelectionMode.RECOMMENDED, ids)).created() ? "CREATED" : "REPLAY"; }
        catch (BusinessException error) { return error.getCode(); }
    }

    private <T> List<T> compete(Callable<T> first, Callable<T> second) throws Exception {
        var start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(10, TimeUnit.SECONDS); return first.call(); });
            var b = pool.submit(() -> { start.await(10, TimeUnit.SECONDS); return second.call(); });
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }

    private TransactionTemplate transaction() {
        var tx = new TransactionTemplate(transactions);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx;
    }

    private void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }

    private Object invoke(Callable<?> call) {
        try { return call.call(); }
        catch (RuntimeException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private void assertCode(String code, Runnable action) {
        assertEquals(code, assertThrows(BusinessException.class, action::run).getCode());
    }

    private void assertNoTrades() {
        for (String table : List.of("customer_order", "order_item", "seat_hold", "seat_hold_item")) { assertEquals(0, count(table), table); }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM session_seat WHERE seat_status='HELD' OR current_hold_id IS NOT NULL", Integer.class));
    }

    private void assertTradeInvariants() {
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM session_seat s LEFT JOIN seat_hold h ON h.id=s.current_hold_id
                LEFT JOIN customer_order o ON o.id=h.order_id
                WHERE (s.seat_status='HELD' AND (h.id IS NULL OR h.hold_status<>'ACTIVE'
                    OR h.session_id<>s.session_id OR o.session_id<>s.session_id))
                   OR (s.seat_status<>'HELD' AND s.current_hold_id IS NOT NULL)
                """, Integer.class));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM seat_hold h JOIN customer_order o ON o.id=h.order_id
                WHERE h.expires_at<>o.payment_expires_at
                   OR (SELECT COUNT(*) FROM seat_hold_item i WHERE i.hold_id=h.id)
                       <> (SELECT COUNT(*) FROM order_item i WHERE i.order_id=o.id)
                   OR (h.hold_status='ACTIVE' AND
                       (SELECT COUNT(*) FROM session_seat s WHERE s.current_hold_id=h.id)
                       <> (SELECT COUNT(*) FROM seat_hold_item i WHERE i.hold_id=h.id))
                """, Integer.class));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM customer_order o WHERE o.total_amount
                    <> (SELECT SUM(i.transaction_price) FROM order_item i WHERE i.order_id=o.id)
                """, Integer.class));
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private long account(String name) { return jdbc.queryForObject("SELECT id FROM account WHERE login_name=?", Long.class, name); }
    private void open(long id) { management.setSalesStatus(id, new UpdateSalesStatus(ON_SALE)); }
    private CreateOrder request(long id, SelectionMode mode, List<Long> ids) { return new CreateOrder(id, mode, ids); }
    private SessionSeat seat(long id, String area, String row, int number) {
        return sessions.listSeats(id).stream().filter(s -> s.areaCodeSnapshot().equals(area)
                && s.rowLabelSnapshot().equals(row) && s.seatNumberSnapshot() == number).findFirst().orElseThrow();
    }
    private List<Long> ids(long id, String area, String row, int... numbers) {
        var selected = new ArrayList<Long>();
        for (int number : numbers) { selected.add(seat(id, area, row, number).id()); }
        return List.copyOf(selected);
    }

    private HttpResponse<String> send(String method, String path, String body, String token, String key) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
            if (token != null) { builder.header("Authorization", "Bearer " + token); }
            if (key != null) { builder.header("Idempotency-Key", key); }
            if (body != null) { builder.header("Content-Type", "application/json"); }
            var publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
            return client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    private JsonNode data(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), response.body());
        var envelope = json.readTree(response.body());
        assertEquals(response.headers().firstValue("X-Request-Id").orElseThrow(), envelope.path("requestId").asString());
        return envelope.path("data");
    }

    @TestConfiguration
    static class TestClock {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
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
