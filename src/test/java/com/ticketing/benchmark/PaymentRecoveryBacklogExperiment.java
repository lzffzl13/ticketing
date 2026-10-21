package com.ticketing.benchmark;

import com.ticketing.catalog.EventService;
import com.ticketing.catalog.SessionManagementService;
import com.ticketing.catalog.SessionService;
import com.ticketing.consistency.ConsistencyService;
import com.ticketing.identity.DemoAccountSeeder;
import com.ticketing.inventory.InventoryModels.SessionSeat;
import com.ticketing.simulator.PaymentSimulatorService;
import com.ticketing.support.TestAuthHelper;
import com.ticketing.trade.OrderService;
import com.ticketing.trade.PaymentQueryService;
import com.ticketing.trade.PaymentRecoveryScanner;
import com.ticketing.trade.PaymentService;
import com.ticketing.trade.TradeModels.OrderDetails;
import com.ticketing.trade.TradeModels.SelectionMode;
import com.ticketing.trade.TradeRequests.CreateOrder;
import com.ticketing.venue.VenueService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.ticketing.catalog.CatalogModels.SalesStatus.ON_SALE;
import static com.ticketing.catalog.CatalogRequests.*;
import static com.ticketing.venue.VenueRequests.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in, retained-data experiment. Ordinary Test/IT discovery does not select this name. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "runPaymentRecoveryBenchmark", matches = "true")
@Import(PaymentRecoveryBacklogExperiment.TestClock.class)
public class PaymentRecoveryBacklogExperiment {
    // Synthetic business time deliberately predates normal modern test history. Never an elapsed-time source.
    private static final Instant FIXTURE_NOW = Instant.parse("2000-01-01T00:00:00.123456Z");
    private static final int SEATS_PER_ROW = 20;
    private static final String DUE = """
            order_status='PENDING_PAYMENT' AND manual_required=FALSE
            AND (recovery_token IS NULL OR recovery_claim_until<=?)
            AND (next_recovery_at<=? OR (next_recovery_at IS NULL
                 AND (payment_expires_at<=? OR cancellation_requested_at IS NOT NULL)))
            """;
    @Autowired private VenueService venues;
    @Autowired private EventService events;
    @Autowired private SessionService sessions;
    @Autowired private SessionManagementService management;
    @Autowired private OrderService orders;
    @Autowired private PaymentService payments;
    @Autowired private PaymentQueryService queries;
    @Autowired private PaymentSimulatorService simulator;
    @Autowired private ConsistencyService consistency;
    @Autowired private DemoAccountSeeder accounts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private HikariDataSource dataSource;
    @Autowired private MutableClock clock;
    @Autowired private JsonMapper json;

    @Test
    void compareCompactBatchScansOfTwoKindsOfAlreadyDueBacklogWithoutDeletingHistory() throws Exception {
        assertEquals("true", System.getProperty("runPaymentRecoveryBenchmark"));
        assertEquals("ticketing_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        String namespace = System.getProperty("recoveryBench.namespace");
        assertNotNull(namespace);
        assertTrue(namespace.matches("RB[A-Z0-9_-]{1,28}"));
        int orderCount = Integer.getInteger("recoveryBench.orders", 50);
        int warmupOrders = Integer.getInteger("recoveryBench.warmupOrders", 5);
        assertTrue(orderCount >= 10 && orderCount <= 500 && orderCount % 10 == 0);
        assertTrue(warmupOrders >= 0 && warmupOrders <= 50 && warmupOrders <= orderCount);
        var batchSizes = Arrays.stream(System.getProperty("recoveryBench.batchSizes", "1,10,50").split(","))
                .map(Integer::parseInt).distinct().toList();
        assertTrue(batchSizes.contains(1), "batch=1 is the serial single-order control");
        assertTrue(batchSizes.size() >= 2 && batchSizes.stream().allMatch(b -> b >= 1 && b <= 500));
        var prefix = Path.of(System.getProperty("recoveryBench.outputPrefix")).toAbsolutePath().normalize();
        assertTrue(prefix.startsWith(Path.of(".local").toAbsolutePath().normalize()), "Evidence belongs under this project's .local");
        Files.createDirectories(prefix.getParent());
        var environment = environment(namespace, orderCount, warmupOrders, batchSizes);
        var cases = new ArrayList<Map<String, Object>>();
        var requestLines = new ArrayList<String>();
        var batchLines = new ArrayList<String>();
        var completionLines = new ArrayList<String>();
        var summaryLines = new ArrayList<String>();
        environment.put("cases", cases);
        save(prefix, environment);
        try {
            clock.set(FIXTURE_NOW);
            assertNoForeignDueOrders(-1);
            long customerId = customerId();
            var venue = venues.createVenue(new CreateVenue(namespace + "-V", "扫描积压实验厅", "自行构造的隔离测试夹具", true));
            var area = venues.createArea(venue.id(), new CreateArea("ONLY", "实验区域", 0));
            var templates = new ArrayList<SeatInput>();
            for (int index = 0; index < orderCount * 2; index++) {
                int row = index / SEATS_PER_ROW;
                int number = index % SEATS_PER_ROW + 1;
                templates.add(new SeatInput("R%04d".formatted(row), row, number,
                        BigDecimal.valueOf(number), BigDecimal.valueOf(row), "ONLY", true));
            }
            venues.addSeats(area.id(), templates);
            var event = events.create(new CreateEvent(namespace + "-E", "支付恢复积压实验", "有限服务层实验，无外部流量", true));
            int caseNumber = 0;
            for (var workload : Workload.values()) {
                var measuredBatches = new ArrayList<>(batchSizes);
                // Alternate case order across workloads; this does not control JVM/cache or machine noise.
                if (workload == Workload.CHANNEL_SUCCESS) { java.util.Collections.reverse(measuredBatches); }
                for (int index = warmupOrders > 0 ? -1 : 0; index < measuredBatches.size(); index++) {
                    boolean warmup = index == -1;
                    int batchSize = warmup ? 1 : measuredBatches.get(index);
                    int count = warmup ? warmupOrders : orderCount;
                    caseNumber++;
                    clock.set(FIXTURE_NOW);
                    // The real scanner has no session filter: refuse any foreign due candidate before touching it.
                    assertNoForeignDueOrders(-1);
                    var startsAt = FIXTURE_NOW.plusSeconds(30 * 86400L);
                    long sessionId = sessions.create(new CreateSession(namespace + "-S" + caseNumber,
                            event.id(), venue.id(), startsAt, startsAt.minusSeconds(3600), startsAt.minusSeconds(300), true,
                            List.of(new PriceTierInput("ONLY", "实验票档", new BigDecimal("100.00"))),
                            List.of(new AreaPriceMapping(area.id(), "ONLY")), List.of())).id();
                    management.setSalesStatus(sessionId, new UpdateSalesStatus(ON_SALE));
                    var caseEvidence = new LinkedHashMap<String, Object>();
                    caseEvidence.put("case", caseNumber); caseEvidence.put("workload", workload);
                    caseEvidence.put("warmup", warmup); caseEvidence.put("batchSize", batchSize);
                    caseEvidence.put("orders", count); caseEvidence.put("sessionId", sessionId);
                    caseEvidence.put("status", "PREPARING");
                    cases.add(caseEvidence);
                    var prepared = new ArrayList<FixtureOrder>();
                    var seats = sessions.listSeats(sessionId).stream().sorted(Comparator
                            .comparingInt(SessionSeat::rowOrderSnapshot).thenComparingInt(SessionSeat::seatNumberSnapshot)).toList();
                    for (int orderIndex = 0; orderIndex < count; orderIndex++) {
                        String key = namespace + "-C" + caseNumber + "-O" + orderIndex;
                        var ids = List.of(seats.get(orderIndex * 2).id(), seats.get(orderIndex * 2 + 1).id());
                        // Save a request before calling the service, so even a failed setup can be found by its original key.
                        environment.put("activeRequest", Map.of("sessionId", sessionId, "customerId", customerId,
                                "requestKey", key, "seatIds", ids));
                        save(prefix, environment);
                        var created = orders.create(customerId, key, new CreateOrder(sessionId, SelectionMode.RECOMMENDED, ids));
                        assertTrue(created.created());
                        var order = created.order();
                        assertEquals(FIXTURE_NOW.plusSeconds(900), order.paymentExpiresAt());
                        String paymentNumber = "";
                        if (workload == Workload.CHANNEL_SUCCESS) {
                            var payment = payments.start(customerId, order.id(), key + "-P");
                            assertEquals("PROCESSING", payment.paymentStatus().name());
                            paymentNumber = payment.paymentNumber();
                            simulator.succeed(paymentNumber); // Independent durable channel success; deliberately no local apply/delivery.
                            assertEquals("PROCESSING", payments.state(order.id()).payments().getFirst().paymentStatus().name());
                        }
                        prepared.add(new FixtureOrder(order, paymentNumber));
                        requestLines.add("%s,%d,%s,%s,%d,%d,%d,%d,%s,%s,%s,%d,%d,%s\n".formatted(namespace,
                                caseNumber, warmup, workload, batchSize, orderIndex, sessionId, customerId,
                                key, order.id(), paymentNumber, ids.getFirst(), ids.getLast(), order.paymentExpiresAt()));
                    }
                    environment.remove("activeRequest");
                    clock.set(FIXTURE_NOW.plusSeconds(900));
                    assertNoForeignDueOrders(sessionId);
                    assertEquals(count, jdbc.queryForObject("SELECT COUNT(*) FROM customer_order WHERE session_id=? AND " + DUE,
                            Integer.class, sessionId, now(), now(), now()));
                    var beforeReport = consistency.inspect(sessionId);
                    assertTrue(beforeReport.consistent(), beforeReport.differences().toString());
                    assertEquals(count, beforeReport.recovery().pendingOrders());
                    caseEvidence.put("beforeConsistency", beforeReport);
                    caseEvidence.put("fixtureReleaseAt", clock.instant());
                    caseEvidence.put("status", "DRAINING");
                    save(prefix, environment);
                    var scanner = new PaymentRecoveryScanner(queries, payments, clock, batchSize);
                    var completed = new LinkedHashMap<Long, Long>();
                    var completionBatches = new LinkedHashMap<Long, Integer>();
                    var batchNanos = new ArrayList<Long>();
                    int batch = 0;
                    long released = System.nanoTime();
                    long lastScanReturned = released;
                    while (completed.size() < count) {
                        assertTrue(batch < count + 2, "Unexpected lack of progress; stop rather than mutate/clean historical data");
                        long started = System.nanoTime();
                        int selected = scanner.scanOnce();
                        long returned = System.nanoTime();
                        lastScanReturned = returned;
                        long elapsed = returned - started;
                        batchNanos.add(elapsed);
                        var terminal = jdbc.queryForList("SELECT id FROM customer_order WHERE session_id=? AND order_status=? ORDER BY id",
                                Long.class, sessionId, workload == Workload.NO_PAYMENT_TIMEOUT ? "CANCELLED" : "PAID");
                        int newlyCompleted = 0;
                        for (long id : terminal) {
                            if (completed.putIfAbsent(id, returned - released) == null) {
                                completionBatches.put(id, batch);
                                newlyCompleted++;
                            }
                        }
                        int remaining = count - completed.size();
                        batchLines.add("%s,%d,%s,%s,%d,%d,%d,%d,%d,%d,%d,%d\n".formatted(namespace, caseNumber,
                                warmup, workload, batchSize, batch, selected, newlyCompleted, remaining, elapsed,
                                returned - released, sessionId));
                        assertEquals(Math.min(batchSize, count - completed.size() + newlyCompleted), selected);
                        assertEquals(selected, newlyCompleted, "Selected candidates are not the same metric as completed outcomes");
                        assertTrue(newlyCompleted > 0, "The healthy compact backlog must progress every scan");
                        batch++;
                    }
                    long drainNanos = lastScanReturned - released;
                    long scanNanos = batchNanos.stream().mapToLong(Long::longValue).sum();
                    for (var fixture : prepared) {
                        var order = fixture.order();
                        completionLines.add("%s,%d,%s,%s,%d,%d,%d,%s,%d,%d\n".formatted(namespace, caseNumber, warmup,
                                workload, batchSize, sessionId, order.id(), fixture.paymentNumber(),
                                completionBatches.get(order.id()), completed.get(order.id())));
                    }
                    verifyFinal(workload, sessionId, prepared, orderCount * 2);
                    var report = consistency.inspect(sessionId);
                    caseEvidence.put("finalConsistency", report);
                    assertTrue(report.consistent(), report.differences().toString());
                    assertEquals(0, report.recovery().pendingOrders());
                    assertEquals(0, report.recovery().unresolvedPayments());
                    assertEquals(0, report.recovery().manualOrders());
                    assertEquals(0, scanner.scanOnce(), "Repeated recovery after draining must select no orders");
                    verifyFinal(workload, sessionId, prepared, orderCount * 2);
                    var sortedBounds = completed.values().stream().mapToLong(Long::longValue).sorted().toArray();
                    double rate = count * 1_000_000_000d / scanNanos;
                    summaryLines.add(String.format(Locale.ROOT, "%s,%d,%s,%s,%d,%d,%d,%d,%d,%d,%d,%.6f,%d\n",
                            namespace, caseNumber, warmup, workload, batchSize, count, batch, scanNanos, drainNanos,
                            percentile(sortedBounds, .5), percentile(sortedBounds, .95), rate, sessionId));
                    caseEvidence.put("scanCalls", batch); caseEvidence.put("scanNanos", scanNanos);
                    caseEvidence.put("drainNanosIncludingObservationGaps", drainNanos);
                    caseEvidence.put("completedOrders", completed.size()); caseEvidence.put("status", "COMPLETED");
                    save(prefix, environment);
                    System.out.printf(Locale.ROOT, "RECOVERY_BACKLOG warmup=%s workload=%s batch=%d orders=%d scans=%d scan_ms=%.3f drain_ms=%.3f session=%d%n",
                            warmup, workload, batchSize, count, batch, scanNanos / 1_000_000d, drainNanos / 1_000_000d, sessionId);
                }
            }
            environment.put("status", "COMPLETED");
        } catch (Exception | AssertionError failure) {
            environment.put("status", "FAILED");
            environment.put("failureType", failure.getClass().getName());
            throw failure;
        } finally {
            environment.put("finishedAtUtc", Instant.now().toString());
            write(prefix, ".requests.csv", "namespace,case,warmup,workload,batch_size,order_index,session_id,customer_id,request_key,order_id,payment_number,seat_1,seat_2,original_payment_expires_at\n", requestLines);
            write(prefix, ".batches.csv", "namespace,case,warmup,workload,batch_size,batch,selected,actual_completed,remaining,scan_elapsed_ns,scan_return_since_release_ns,session_id\n", batchLines);
            write(prefix, ".completions.csv", "namespace,case,warmup,workload,batch_size,session_id,order_id,payment_number,first_observed_batch,completion_upper_bound_ns\n", completionLines);
            write(prefix, ".summary.csv", "namespace,case,warmup,workload,batch_size,orders,scan_calls,total_scan_ns,drain_ns_including_observation_gaps,median_completion_upper_bound_ns,p95_completion_upper_bound_ns,completed_orders_per_scan_second,session_id\n", summaryLines);
            save(prefix, environment);
        }
    }

    private void assertNoForeignDueOrders(long ownSessionId) {
        // Same eligibility as PaymentMapper.dueRecoveryOrders, without cursor/limit; scanOnce itself remains unchanged.
        var foreign = jdbc.queryForList("SELECT id FROM customer_order WHERE session_id<>? AND " + DUE + " ORDER BY id",
                Long.class, ownSessionId, Timestamp.from(FIXTURE_NOW.plusSeconds(900)),
                Timestamp.from(FIXTURE_NOW.plusSeconds(900)), Timestamp.from(FIXTURE_NOW.plusSeconds(900)));
        assertTrue(foreign.isEmpty(), "Foreign due order IDs=" + foreign + "; stop and preserve history; run without concurrent IT/application writes");
    }

    private void verifyFinal(Workload workload, long sessionId, List<FixtureOrder> fixtures, int seatCount) {
        int count = fixtures.size();
        boolean unpaid = workload == Workload.NO_PAYMENT_TIMEOUT;
        String expectedOrder = unpaid ? "CANCELLED" : "PAID";
        for (var fixture : fixtures) {
            var original = fixture.order();
            var current = orders.get(original.customerId(), original.id());
            assertEquals(expectedOrder, current.orderStatus().name());
            assertEquals(unpaid ? "EXPIRED" : "CONSUMED", current.hold().status().name());
            assertEquals(original.items(), current.items());
            assertEquals(original.paymentExpiresAt(), current.paymentExpiresAt());
            assertEquals(original.paymentExpiresAt(), current.hold().expiresAt());
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM seat_hold_item WHERE hold_id=?", Integer.class, current.hold().id()));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM session_seat WHERE current_hold_id=?", Integer.class, current.hold().id()));
            var state = payments.state(original.id());
            assertEquals(0, state.consecutiveFailures()); assertFalse(state.manualRequired());
            assertNull(state.recoveryClaimUntil());
            assertEquals(unpaid ? 0 : 1, state.payments().size());
            if (!unpaid) {
                assertEquals("SUCCEEDED", state.payments().getFirst().paymentStatus().name());
                assertEquals("SUCCEEDED", simulator.find(fixture.paymentNumber()).orElseThrow().paymentStatus().name());
                assertTrue(simulator.notifications(fixture.paymentNumber()).stream().allMatch(n -> n.deliveredAt() == null),
                        "This case measures active recovery, not notification delivery");
            }
        }
        assertEquals(count, jdbc.queryForObject("SELECT COUNT(*) FROM customer_order WHERE session_id=?", Integer.class, sessionId));
        assertEquals(count * 2, jdbc.queryForObject("SELECT COUNT(*) FROM order_item WHERE session_id=?", Integer.class, sessionId));
        assertEquals(count, jdbc.queryForObject("SELECT COUNT(*) FROM seat_hold WHERE session_id=?", Integer.class, sessionId));
        assertEquals(count * 2, jdbc.queryForObject("SELECT COUNT(*) FROM seat_hold_item WHERE session_id=?", Integer.class, sessionId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM customer_order WHERE session_id=? AND (recovery_token IS NOT NULL OR recovery_claim_until IS NOT NULL)", Integer.class, sessionId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM session_seat WHERE session_id=? AND (seat_status='HELD' OR current_hold_id IS NOT NULL)", Integer.class, sessionId));
        assertEquals(unpaid ? seatCount : seatCount - count * 2, jdbc.queryForObject("SELECT COUNT(*) FROM session_seat WHERE session_id=? AND seat_status='AVAILABLE'", Integer.class, sessionId));
        assertEquals(unpaid ? 0 : count * 2, jdbc.queryForObject("SELECT COUNT(*) FROM session_seat WHERE session_id=? AND seat_status='SOLD'", Integer.class, sessionId));
        assertEquals(unpaid ? 0 : count * 2, jdbc.queryForObject("SELECT COUNT(*) FROM ticket WHERE session_id=? AND ticket_status='VALID'", Integer.class, sessionId));
        assertEquals(unpaid ? 0 : count * 2, jdbc.queryForObject("SELECT COUNT(*) FROM ticket WHERE session_id=?", Integer.class, sessionId));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM (SELECT session_seat_id FROM ticket WHERE session_id=?
                  AND ticket_status IN ('VALID','REFUNDING','CHECKED_IN') GROUP BY session_seat_id HAVING COUNT(*)>1) duplicates
                """, Integer.class, sessionId));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM (SELECT i.session_seat_id FROM seat_hold_item i JOIN seat_hold h ON h.id=i.hold_id
                  WHERE i.session_id=? AND h.hold_status='ACTIVE' GROUP BY i.session_seat_id HAVING COUNT(*)>1) duplicates
                """, Integer.class, sessionId));
    }

    private Map<String, Object> environment(String namespace, int orders, int warmup, List<Integer> batches) {
        var result = new LinkedHashMap<String, Object>();
        result.put("status", "STARTED"); result.put("namespace", namespace);
        result.put("startedAtUtc", Instant.now().toString()); result.put("database", "ticketing_test");
        result.put("mysqlVersion", jdbc.queryForObject("SELECT VERSION()", String.class));
        result.put("mysqlDefaultIsolation", jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
        result.put("hikariMaximumPoolSize", dataSource.getMaximumPoolSize());
        result.put("ordersPerMeasuredCase", orders); result.put("warmupOrdersPerWorkload", warmup);
        result.put("measuredBatchSizes", batches); result.put("syntheticBusinessClockUtc", FIXTURE_NOW.toString());
        result.put("syntheticReleaseClockUtc", FIXTURE_NOW.plusSeconds(900).toString());
        result.put("originalPaymentWindowSeconds", 900);
        result.put("clockMeaning", "Synthetic year-2000 business fixtures isolate modern historical orders. Advancing the test Clock does not wait 15 real minutes or measure elapsed time.");
        result.put("generation", "One shared virtual venue; 20 enabled seats per row, one area/tier/aisle segment; 100.00 each; aligned disjoint two-seat orders; forbidNewOrphan=true; one fresh session per case; no randomness or history deletion");
        result.put("workloads", List.of("NO_PAYMENT_TIMEOUT: no payment attempt, due order safely cancels/releases", "CHANNEL_SUCCESS: accepted before deadline, independent channel success saved, local PROCESSING until overdue scan issues tickets"));
        result.put("scanTiming", "System.nanoTime immediately around real scanOnce, including candidate query, each full recovery and transaction commits. Fixture generation, assertions, consistency reports, output and completion observation SQL excluded from total_scan_ns.");
        result.put("completionBounds", "All backlog becomes eligible at one release. Each order is first observed terminal after its scan returns; completion_upper_bound_ns uses that scan return minus release, an upper bound on local completion, not the exact transaction commit timestamp. It includes preceding observation SQL/gaps; percentile uses nearest rank.");
        result.put("throughput", "Completed orders / sum of scanOnce durations. drain_ns_including_observation_gaps includes SQL observations between compact scans; no intentional scheduler wait.");
        result.put("isolation", "Before each case and release, the production due predicate must find zero foreign candidates; otherwise stop and preserve evidence. Run serially with ITs, other experiments and application writers.");
        result.put("limits", "Single JVM and scanner, finite healthy backlog, no HTTP/real-time arrival/fault load; background scanners disabled by test profile. No real 15-minute wait or default 1-second fixed-delay scheduler. No production SLA, automatic release latency or capacity claim; one run per batch/workload, warmup does not control JVM/cache/OS noise.");
        return result;
    }

    private long customerId() {
        var ids = jdbc.queryForList("SELECT id FROM account WHERE login_name='consumer1' AND role='CONSUMER' AND enabled=TRUE", Long.class);
        if (ids.isEmpty()) {
            accounts.seed(TestAuthHelper.PASSWORD);
            ids = jdbc.queryForList("SELECT id FROM account WHERE login_name='consumer1' AND role='CONSUMER' AND enabled=TRUE", Long.class);
        }
        assertEquals(1, ids.size());
        return ids.getFirst();
    }

    private Timestamp now() { return Timestamp.from(clock.instant()); }
    private static long percentile(long[] sorted, double quantile) { return sorted[(int) Math.ceil(sorted.length * quantile) - 1]; }
    private void save(Path prefix, Map<String, Object> environment) throws Exception {
        Files.writeString(Path.of(prefix + ".database.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(environment), StandardCharsets.UTF_8);
    }
    private static void write(Path prefix, String suffix, String header, List<String> lines) throws Exception {
        Files.writeString(Path.of(prefix + suffix), header + String.join("", lines), StandardCharsets.UTF_8);
    }
    private enum Workload { NO_PAYMENT_TIMEOUT, CHANNEL_SUCCESS }
    private record FixtureOrder(OrderDetails order, String paymentNumber) { }

    @TestConfiguration
    static class TestClock {
        @Bean @Primary MutableClock recoveryBenchmarkClock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        private volatile Instant at = FIXTURE_NOW;
        void set(Instant instant) { at = instant; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) { throw new IllegalArgumentException("Experiment Clock uses UTC"); }
            return this;
        }
        @Override public Instant instant() { return at; }
    }
}
