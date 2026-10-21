package com.ticketing.trade;

import com.ticketing.catalog.EventService;
import com.ticketing.catalog.SessionManagementService;
import com.ticketing.catalog.SessionService;
import com.ticketing.common.web.BusinessException;
import com.ticketing.consistency.ConsistencyService;
import com.ticketing.identity.DemoAccountSeeder;
import com.ticketing.inventory.InventoryModels.SessionSeat;
import com.ticketing.support.TestAuthHelper;
import com.ticketing.support.Week2Fixtures;
import com.ticketing.venue.VenueService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.ticketing.catalog.CatalogModels.SalesStatus.ON_SALE;
import static com.ticketing.catalog.CatalogRequests.*;
import static com.ticketing.trade.TradeModels.OrderStatus.PENDING_PAYMENT;
import static com.ticketing.trade.TradeModels.SelectionMode.RECOMMENDED;
import static com.ticketing.venue.VenueRequests.*;
import static org.junit.jupiter.api.Assertions.*;

/** Finite correctness experiment against real MySQL commits; it does not measure capacity. */
@SpringBootTest
@ActiveProfiles("test")
@Import(Week7InventoryReliabilityIT.TestClock.class)
class Week7InventoryReliabilityIT {
    private static final Instant FIXTURE_NOW = Instant.parse("2026-10-21T02:00:00.123456Z");
    private static final int CUSTOMER_COUNT = 8;
    private static final int SEGMENT_COUNT = 3;
    private static final int SEATS_PER_SEGMENT = 8;
    private static final int REQUESTS_PER_SEGMENT = 4;
    private static final int WORKERS = SEGMENT_COUNT * REQUESTS_PER_SEGMENT;
    private static final Path EVIDENCE = Path.of("target", "week7-inventory-reliability.json");
    @Autowired private VenueService venues;
    @Autowired private EventService events;
    @Autowired private SessionService sessions;
    @Autowired private SessionManagementService management;
    @Autowired private OrderService orders;
    @Autowired private DemoAccountSeeder accounts;
    @Autowired private ConsistencyService consistency;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private HikariDataSource dataSource;
    @Autowired private JsonMapper json;

    @Test
    void repeatedMultiUserOverlapsCommitOnlyWholeDisjointWinningGroups() throws Exception {
        int roundsPerWorkload = Integer.getInteger("week7.inventory.rounds", 10);
        assertTrue(roundsPerWorkload >= 1 && roundsPerWorkload <= 100);
        assertEquals("ticketing_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        var evidence = environment(roundsPerWorkload);
        var rounds = new ArrayList<Map<String, Object>>();
        evidence.put("rounds", rounds);
        save(evidence);
        try {
            // Like the other IT fixtures, this owns and resets only the dedicated test database.
            // Run serially with other ITs and benchmarks: their cleanup must not race these requests.
            Week2Fixtures.resetTestDatabase(jdbc);
            accounts.seed(TestAuthHelper.PASSWORD);
            var customers = customers();
            var venue = venues.createVenue(new CreateVenue("W7-INVENTORY", "第七周锁座实验厅", "自行生成的隔离测试夹具", true));
            var area = venues.createArea(venue.id(), new CreateArea("ONLY", "实验区域", 0));
            var templates = new ArrayList<SeatInput>();
            for (int row = 0; row < SEGMENT_COUNT; row++) {
                for (int number = 1; number <= SEATS_PER_SEGMENT; number++) {
                    templates.add(new SeatInput("R" + row, row, number, BigDecimal.valueOf(number),
                            BigDecimal.valueOf(row), "ONLY", true));
                }
            }
            venues.addSeats(area.id(), templates);
            var event = events.create(new CreateEvent("W7-INVENTORY", "重叠座位可靠性实验", "固定布局，无随机数据", true));
            for (var workload : Workload.values()) {
                for (int round = 0; round < roundsPerWorkload; round++) {
                    long sessionId = sessions.create(new CreateSession("W7-" + workload + "-" + round,
                            event.id(), venue.id(), Week2Fixtures.START, Week2Fixtures.START.minusSeconds(3600),
                            Week2Fixtures.START.minusSeconds(300), true,
                            List.of(new PriceTierInput("ONLY", "实验票档", new BigDecimal("100.00"))),
                            List.of(new AreaPriceMapping(area.id(), "ONLY")), List.of())).id();
                    management.setSalesStatus(sessionId, new UpdateSalesStatus(ON_SALE));
                    var requests = requests(workload, round, sessionId, customers);
                    var results = new ArrayList<Outcome>();
                    var roundEvidence = new LinkedHashMap<String, Object>();
                    roundEvidence.put("workload", workload);
                    roundEvidence.put("round", round);
                    roundEvidence.put("sessionId", sessionId);
                    roundEvidence.put("status", "STARTED");
                    roundEvidence.put("requestsInSubmissionOrder", requests);
                    roundEvidence.put("outcomes", results);
                    rounds.add(roundEvidence);
                    save(evidence);
                    runWave(requests, results);
                    roundEvidence.put("counts", counts(results));
                    // Save actual outcomes before any assertion, including an unexpected error code.
                    save(evidence);
                    assertRound(workload, sessionId, results);
                    var report = consistency.inspect(sessionId);
                    roundEvidence.put("consistency", report);
                    save(evidence);
                    assertTrue(report.consistent(), report.differences().toString());
                    assertTrue(report.differences().isEmpty());
                    assertEquals(SEGMENT_COUNT * SEATS_PER_SEGMENT, report.counts().seats());
                    assertEquals(counts(results).get("created").intValue(), report.counts().orders());
                    assertEquals(report.counts().orders(), report.recovery().pendingOrders());
                    assertEquals(0, report.counts().tickets());
                    roundEvidence.put("wholeGroupsAndUniqueOwnersVerified", true);
                    roundEvidence.put("status", "PASSED");
                    save(evidence);
                }
            }
            evidence.put("status", "PASSED");
        } catch (Exception | AssertionError failure) {
            evidence.put("status", "FAILED");
            evidence.put("failureType", failure.getClass().getName());
            throw failure;
        } finally {
            var all = rounds.stream().flatMap(r -> outcomes(r).stream()).toList();
            evidence.put("totals", counts(all));
            evidence.put("completedRounds", rounds.stream().filter(r -> "PASSED".equals(r.get("status"))).count());
            evidence.put("finishedAtUtc", Instant.now().toString());
            save(evidence);
        }
    }

    private List<Long> customers() {
        var result = new ArrayList<Long>();
        for (int index = 1; index <= CUSTOMER_COUNT; index++) {
            String name = index <= 2 ? "consumer" + index : "w7consumer" + index;
            if (index > 2) {
                // Copy only a test password hash; no new production identity or login API is needed.
                assertEquals(1, jdbc.update("""
                        INSERT INTO account(login_name,password_hash,role,enabled,created_at,updated_at)
                        SELECT ?,password_hash,'CONSUMER',TRUE,created_at,updated_at
                        FROM account WHERE login_name='consumer1'
                        """, name));
            }
            result.add(jdbc.queryForObject("SELECT id FROM account WHERE login_name=?", Long.class, name));
        }
        return List.copyOf(result);
    }

    private List<Attempt> requests(Workload workload, int round, long sessionId, List<Long> customers) {
        var seats = sessions.listSeats(sessionId);
        var result = new ArrayList<Attempt>();
        for (int segment = 0; segment < SEGMENT_COUNT; segment++) {
            String row = "R" + segment;
            var segmentSeats = seats.stream().filter(s -> s.rowLabelSnapshot().equals(row))
                    .sorted(Comparator.comparingInt(SessionSeat::seatNumberSnapshot)).toList();
            for (int choice = 0; choice < REQUESTS_PER_SEGMENT; choice++) {
                int worker = segment * REQUESTS_PER_SEGMENT + choice;
                int from = workload == Workload.OVERLAP_CHAIN && choice < 3 ? choice * 2 : 0;
                int length = workload == Workload.ANCHOR_PREFIX ? (choice + 1) * 2
                        : choice == 3 ? SEATS_PER_SEGMENT : 4;
                var ids = segmentSeats.subList(from, from + length).stream().map(SessionSeat::id).toList();
                // Input ordering must not change identity normalization or lock ordering.
                if ((worker + round) % 2 != 0) { ids = ids.reversed(); }
                result.add(new Attempt(worker, segment, customers.get(worker % CUSTOMER_COUNT), sessionId,
                        "W7-" + workload + "-R" + round + "-W" + worker, ids));
            }
        }
        // Deterministic permutations reduce fixed submission bias; scheduler winners remain unspecified.
        Collections.rotate(result, round % WORKERS);
        if (round % 2 != 0) { Collections.reverse(result); }
        return List.copyOf(result);
    }

    private void runWave(List<Attempt> requests, List<Outcome> results) throws Exception {
        var ready = new CountDownLatch(WORKERS);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(WORKERS)) {
            var futures = new ArrayList<Future<Outcome>>();
            for (var attempt : requests) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(release.await(20, TimeUnit.SECONDS), "Wave was not released");
                    try {
                        var created = orders.create(attempt.customerId(), attempt.requestKey(),
                                new TradeRequests.CreateOrder(attempt.sessionId(), RECOMMENDED, attempt.seatIds()));
                        return new Outcome(attempt, created.created() ? "CREATED" : "UNEXPECTED_REPLAY",
                                created.order().id(), created.order().hold().id(), null);
                    } catch (BusinessException conflict) {
                        return new Outcome(attempt, conflict.getCode(), null, null, null);
                    } catch (RuntimeException unexpected) {
                        return new Outcome(attempt, "UNEXPECTED_FAILURE", null, null, unexpected.getClass().getName());
                    }
                }));
            }
            try {
                assertTrue(ready.await(20, TimeUnit.SECONDS), "All workers must reach the start latch");
                release.countDown();
                for (var future : futures) { results.add(future.get(40, TimeUnit.SECONDS)); }
            } finally {
                release.countDown();
            }
        }
    }

    private void assertRound(Workload workload, long sessionId, List<Outcome> results) {
        assertEquals(WORKERS, results.size());
        assertEquals(0, counts(results).get("unexpectedFailures"), results.toString());
        var winners = results.stream().filter(r -> r.result().equals("CREATED")).toList();
        var expectedOwners = new HashMap<Long, Long>();
        for (var winner : winners) {
            var attempt = winner.attempt();
            var order = orders.get(attempt.customerId(), winner.orderId());
            var expectedSeats = new HashSet<>(attempt.seatIds());
            assertEquals(PENDING_PAYMENT, order.orderStatus());
            assertEquals("ACTIVE", order.hold().status().name());
            assertEquals(FIXTURE_NOW.plusSeconds(900), order.paymentExpiresAt());
            assertEquals(order.paymentExpiresAt(), order.hold().expiresAt());
            assertEquals(new BigDecimal("100.00").multiply(BigDecimal.valueOf(expectedSeats.size())), order.totalAmount());
            assertEquals(expectedSeats, order.items().stream().map(TradeModels.OrderItem::sessionSeatId).collect(Collectors.toSet()));
            assertEquals(expectedSeats.size(), order.items().size());
            assertEquals(expectedSeats, new HashSet<>(jdbc.queryForList(
                    "SELECT session_seat_id FROM seat_hold_item WHERE hold_id=?", Long.class, winner.holdId())));
            assertEquals(expectedSeats, new HashSet<>(jdbc.queryForList(
                    "SELECT id FROM session_seat WHERE current_hold_id=? AND seat_status='HELD'", Long.class, winner.holdId())));
            for (var seatId : expectedSeats) {
                assertNull(expectedOwners.put(seatId, winner.holdId()), "Two successful requests must never own the same seat");
            }
        }
        for (int segment = 0; segment < SEGMENT_COUNT; segment++) {
            int selectedSegment = segment;
            long count = winners.stream().filter(w -> w.attempt().segment() == selectedSegment).count();
            if (workload == Workload.ANCHOR_PREFIX) { assertEquals(1, count); }
            else { assertTrue(count >= 1 && count <= 2, "Each overlap chain has one or two disjoint winners"); }
        }
        for (var result : results) {
            assertEquals(result.result().equals("CREATED") ? 1 : 0, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM customer_order WHERE customer_id=? AND idempotency_key=?
                    """, Integer.class, result.attempt().customerId(), result.attempt().requestKey()));
            if (!result.result().equals("CREATED")) {
                assertEquals("SEAT_NOT_AVAILABLE", result.result(), results.toString());
                assertTrue(result.attempt().seatIds().stream().anyMatch(expectedOwners::containsKey),
                        "A rejected group must actually overlap a committed winner");
            }
        }
        for (var seat : jdbc.queryForList("SELECT id,seat_status,current_hold_id FROM session_seat WHERE session_id=?", sessionId)) {
            long id = ((Number) seat.get("id")).longValue();
            assertEquals(expectedOwners.containsKey(id) ? "HELD" : "AVAILABLE", seat.get("seat_status"));
            assertEquals(expectedOwners.get(id), seat.get("current_hold_id") == null ? null
                    : ((Number) seat.get("current_hold_id")).longValue());
        }
        assertEquals(winners.size(), jdbc.queryForObject("SELECT COUNT(*) FROM customer_order WHERE session_id=?", Integer.class, sessionId));
        assertEquals(winners.size(), jdbc.queryForObject("SELECT COUNT(*) FROM seat_hold WHERE session_id=?", Integer.class, sessionId));
        assertEquals(expectedOwners.size(), jdbc.queryForObject("SELECT COUNT(*) FROM order_item WHERE session_id=?", Integer.class, sessionId));
        assertEquals(expectedOwners.size(), jdbc.queryForObject("SELECT COUNT(*) FROM seat_hold_item WHERE session_id=?", Integer.class, sessionId));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                  SELECT i.session_seat_id FROM seat_hold_item i JOIN seat_hold h ON h.id=i.hold_id
                  WHERE i.session_id=? AND h.hold_status='ACTIVE' GROUP BY i.session_seat_id HAVING COUNT(*)>1
                ) duplicate_active_holds
                """, Integer.class, sessionId));
        assertTrue(expectedOwners.size() <= SEGMENT_COUNT * SEATS_PER_SEGMENT);
    }

    private Map<String, Object> environment(int rounds) {
        var result = new LinkedHashMap<String, Object>();
        result.put("status", "STARTED");
        result.put("startedAtUtc", Instant.now().toString());
        result.put("fixtureClockUtc", FIXTURE_NOW.toString());
        result.put("clockMeaning", "Fixed business-time fixture, not the experiment's actual execution timestamp");
        result.put("database", jdbc.queryForObject("SELECT DATABASE()", String.class));
        result.put("mysqlVersion", jdbc.queryForObject("SELECT VERSION()", String.class));
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        result.put("hikariMaximumPoolSize", dataSource.getMaximumPoolSize());
        result.put("roundsPerWorkload", rounds);
        result.put("workloads", List.of("ANCHOR_PREFIX: each segment requests [1..2], [1..4], [1..6], [1..8]",
                "OVERLAP_CHAIN: each segment requests [1..4], [3..6], [5..8], [1..8]"));
        result.put("customers", CUSTOMER_COUNT);
        result.put("workerThreads", WORKERS);
        result.put("seatsPerSession", SEGMENT_COUNT * SEATS_PER_SEGMENT);
        result.put("generation", "3 rows of 8 enabled seats, one shared area/tier, one aisle segment in each row; 100.00 per seat; forbidNewOrphan=true; new session each round; no randomness");
        result.put("expectedRequests", Workload.values().length * rounds * WORKERS);
        result.put("concurrency", "12 ready-latch workers released together, 8 distinct consumers, unique keys; the Hikari pool may limit active DB connections; same-session writers serialize at the session row lock");
        result.put("orderWriteIsolation", "READ_COMMITTED declared by the existing OrderTransactionService");
        result.put("submissionOrder", "Rotate by round modulo 12, reverse odd rounds; reverse seat IDs on alternating requests; no assertion about which user wins");
        result.put("limits", "Finite in-process service calls only, no HTTP or multi-JVM test; no latency/throughput claim; checks inventory allocation before payment, not later payment/check-in races; test profile disables background scanners");
        return result;
    }

    private Map<String, Integer> counts(List<Outcome> outcomes) {
        int created = (int) outcomes.stream().filter(r -> r.result().equals("CREATED")).count();
        int conflicts = (int) outcomes.stream().filter(r -> r.result().equals("SEAT_NOT_AVAILABLE")).count();
        int allocatedSeats = outcomes.stream().filter(r -> r.result().equals("CREATED"))
                .mapToInt(r -> r.attempt().seatIds().size()).sum();
        return Map.of("completedRequests", outcomes.size(), "created", created, "businessConflicts", conflicts,
                "unexpectedFailures", outcomes.size() - created - conflicts, "allocatedSeats", allocatedSeats);
    }

    @SuppressWarnings("unchecked")
    private List<Outcome> outcomes(Map<String, Object> round) { return (List<Outcome>) round.get("outcomes"); }

    private void save(Map<String, Object> evidence) throws Exception {
        Files.createDirectories(EVIDENCE.getParent());
        Files.writeString(EVIDENCE, json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence), StandardCharsets.UTF_8);
    }

    enum Workload { ANCHOR_PREFIX, OVERLAP_CHAIN }
    record Attempt(int worker, int segment, long customerId, long sessionId, String requestKey, List<Long> seatIds) {
        Attempt { seatIds = List.copyOf(seatIds); }
    }
    record Outcome(Attempt attempt, String result, Long orderId, Long holdId, String unexpectedErrorType) { }

    @TestConfiguration
    static class TestClock {
        @Bean @Primary Clock week7InventoryClock() { return Clock.fixed(FIXTURE_NOW, ZoneOffset.UTC); }
    }
}
