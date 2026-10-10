package com.ticketing.benchmark;

import com.ticketing.catalog.EventService;
import com.ticketing.catalog.SessionManagementService;
import com.ticketing.catalog.SessionService;
import com.ticketing.identity.DemoAccountSeeder;
import com.ticketing.inventory.InventoryService;
import com.ticketing.support.TestAuthHelper;
import com.ticketing.trade.OrderService;
import com.ticketing.trade.TradeModels.SelectionMode;
import com.ticketing.trade.TradeRequests.CreateOrder;
import com.ticketing.venue.VenueService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.ticketing.catalog.CatalogModels.SalesStatus.ON_SALE;
import static com.ticketing.catalog.CatalogRequests.*;
import static com.ticketing.venue.VenueRequests.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in experiment; its name intentionally does not match normal Test/IT discovery. */
@SpringBootTest
@ActiveProfiles("test")
public class OrderHoldBenchmarkExperiment {
    private static final int SEATS_PER_ROW = 20;
    @Autowired private VenueService venues;
    @Autowired private EventService events;
    @Autowired private SessionService sessions;
    @Autowired private SessionManagementService management;
    @Autowired private InventoryService inventory;
    @Autowired private OrderService orders;
    @Autowired private DemoAccountSeeder accounts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private HikariDataSource dataSource;
    @Autowired private JsonMapper json;

    @Test
    void measureCommittedTwoSeatOrdersOnOneSessionWithoutDeletingExistingData() throws Exception {
        assertEquals("true", System.getProperty("runOrderHoldBenchmark"),
                "Invoke only through scripts/benchmark-order-holds.ps1 with the explicit opt-in property");
        assertEquals("ticketing_test", jdbc.queryForObject("SELECT DATABASE()", String.class),
                "The experiment may write only the isolated ticketing_test database");
        var namespace = System.getProperty("bench.namespace");
        assertNotNull(namespace, "Benchmark namespace is required");
        assertTrue(namespace.matches("HB[A-Z0-9_-]{1,28}"));
        var sizes = integers("bench.seatCounts", "100,500");
        var concurrencyLevels = integers("bench.concurrency", "1,4,8");
        int batches = Integer.getInteger("bench.batches", 4);
        int warmupBatches = Integer.getInteger("bench.warmupBatches", 1);
        assertTrue(batches > 0 && batches <= 100 && warmupBatches >= 0 && warmupBatches <= 20);
        for (int size : sizes) {
            assertTrue(size >= SEATS_PER_ROW && size <= 10000 && size % SEATS_PER_ROW == 0);
            for (int concurrency : concurrencyLevels) {
                assertTrue(concurrency > 0 && concurrency <= 32);
                assertTrue(2L * (batches + warmupBatches) * concurrency <= size,
                        "Each case needs a distinct two-seat pair for every warmup and measured order");
            }
        }
        var prefix = Path.of(System.getProperty("bench.outputPrefix")).toAbsolutePath().normalize();
        var localDirectory = Path.of(".local").toAbsolutePath().normalize();
        assertTrue(prefix.startsWith(localDirectory), "Benchmark artifacts must remain under this project's .local");
        Files.createDirectories(prefix.getParent());
        var samplesPath = Path.of(prefix + ".samples.csv");
        var summaryPath = Path.of(prefix + ".summary.csv");
        var fixturePath = Path.of(prefix + ".requests.csv");
        var databasePath = Path.of(prefix + ".database.json");
        Files.writeString(samplesPath,
                "namespace,seat_count,concurrency,batch,worker,elapsed_ns,session_id,order_id,request_key\n", StandardCharsets.UTF_8);
        Files.writeString(summaryPath,
                "namespace,seat_count,concurrency,measured_batches,measured_orders,warmup_orders,mean_ms,median_ms,p95_ms,measured_wave_ms,successful_orders_per_second,session_id,verified_held_seats\n", StandardCharsets.UTF_8);
        Files.writeString(fixturePath,
                "namespace,seat_count,concurrency,warmup,batch,worker,session_id,request_key,seat_1,seat_2\n", StandardCharsets.UTF_8);
        var environment = new LinkedHashMap<String, Object>();
        environment.put("status", "STARTED");
        environment.put("namespace", namespace);
        environment.put("database", "ticketing_test");
        environment.put("mysqlVersion", jdbc.queryForObject("SELECT VERSION()", String.class));
        environment.put("mysqlSessionDefaultIsolation", jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
        environment.put("orderWriteIsolation", "READ_COMMITTED declared by OrderTransactionService");
        environment.put("hikariMaximumPoolSize", dataSource.getMaximumPoolSize());
        environment.put("baseline", "Current complete OrderService.create service round trip; concurrency=1 is the serial control for the same seat count");
        environment.put("workload", "One session per case; distinct aligned two-seat pairs; same customer, distinct request keys; finite barrier-released closed waves");
        environment.put("dataGeneration", "One area, one 380.00 tier, 20 enabled seats per row, one aisle segment; row labels R0000..; no randomness; forbidNewOrphan=true");
        environment.put("timing", "System.nanoTime immediately around OrderService.create, including transaction start/commit, connection and row-lock waits, validations and result loading; HTTP/auth, fixture generation, barrier wait, warmup, assertions and file I/O excluded");
        environment.put("throughput", "Successful measured orders / sum of measured wave durations; each wave measured from barrier release through all future completions; setup and gaps between waves excluded");
        environment.put("percentiles", "Nearest rank sorted[ceil(n*q)-1]; small finite samples, not a capacity estimate");
        environment.put("seatCounts", sizes);
        environment.put("concurrencyLevels", concurrencyLevels);
        environment.put("warmupBatchesPerCase", warmupBatches);
        environment.put("measuredBatchesPerCase", batches);
        var fixtures = new ArrayList<Object>();
        environment.put("fixtures", fixtures);
        saveEnvironment(databasePath, environment);
        try {
            long customerId = customerId();
            int caseNumber = 0;
            for (int size : sizes) {
                var venue = venues.createVenue(new CreateVenue(namespace + "-V" + size, "锁座实验虚拟厅", "自行构造，隔离测试库", true));
                var area = venues.createArea(venue.id(), new CreateArea("AREA-A", "实验区域", 0));
                var templateSeats = new ArrayList<SeatInput>();
                for (int index = 0; index < size; index++) {
                    int row = index / SEATS_PER_ROW;
                    int number = index % SEATS_PER_ROW + 1;
                    templateSeats.add(new SeatInput("R%04d".formatted(row), row, number,
                            BigDecimal.valueOf(number), BigDecimal.valueOf(row), "ONLY", true));
                }
                venues.addSeats(area.id(), templateSeats);
                var event = events.create(new CreateEvent(namespace + "-E" + size, "锁座实验", "仅测试库的有限波次基线", true));
                for (int concurrency : concurrencyLevels) {
                    caseNumber++;
                    var startsAt = Instant.now().plusSeconds(30 * 86400L);
                    var session = sessions.create(new CreateSession(namespace + "-S" + caseNumber, event.id(), venue.id(),
                            startsAt, startsAt.minusSeconds(3600), startsAt.minusSeconds(300), true,
                            List.of(new PriceTierInput("TIER-A", "实验票档", new BigDecimal("380.00"))),
                            List.of(new AreaPriceMapping(area.id(), "TIER-A")), List.of()));
                    management.setSalesStatus(session.id(), new UpdateSalesStatus(ON_SALE));
                    var seats = inventory.listSeats(session.id()).stream()
                            .sorted(Comparator.comparingInt(com.ticketing.inventory.InventoryModels.SessionSeat::rowOrderSnapshot)
                                    .thenComparingInt(com.ticketing.inventory.InventoryModels.SessionSeat::seatNumberSnapshot)).toList();
                    assertEquals(size, seats.size());
                    fixtures.add(java.util.Map.of("case", caseNumber, "seatCount", size, "concurrency", concurrency,
                            "venueId", venue.id(), "sessionId", session.id(), "sessionCode", session.code()));
                    saveEnvironment(databasePath, environment);
                    var caseSamples = new ArrayList<Sample>();
                    long measuredWaveNanos = 0;
                    try (var workers = Executors.newFixedThreadPool(concurrency)) {
                        for (int batch = 0; batch < warmupBatches + batches; batch++) {
                            boolean warmup = batch < warmupBatches;
                            int measuredBatch = batch - warmupBatches;
                            var ready = new CountDownLatch(concurrency);
                            var release = new CountDownLatch(1);
                            var futures = new ArrayList<java.util.concurrent.Future<Sample>>();
                            for (int worker = 0; worker < concurrency; worker++) {
                                int pairIndex = (batch * concurrency + worker) * 2;
                                var seatIds = List.of(seats.get(pairIndex).id(), seats.get(pairIndex + 1).id());
                                String key = namespace + "-C" + caseNumber + "-B" + batch + "-W" + worker;
                                // Persist every non-sensitive request before submitting it; no data is deleted on failure.
                                append(fixturePath, "%s,%d,%d,%s,%d,%d,%d,%s,%d,%d%n".formatted(namespace, size,
                                        concurrency, warmup, batch, worker, session.id(), key, seatIds.get(0), seatIds.get(1)));
                                int workerNumber = worker;
                                futures.add(workers.submit(() -> {
                                    ready.countDown();
                                    assertTrue(release.await(30, TimeUnit.SECONDS), "Wave release timed out");
                                    long started = System.nanoTime();
                                    var result = orders.create(customerId, key, new CreateOrder(session.id(), SelectionMode.RECOMMENDED, seatIds));
                                    long elapsed = System.nanoTime() - started;
                                    assertTrue(result.created(), "Measured calls must create new orders rather than replay old requests");
                                    assertEquals(2, result.order().items().size());
                                    return new Sample(size, concurrency, measuredBatch, workerNumber, elapsed,
                                            session.id(), result.order().id(), key);
                                }));
                            }
                            long waveElapsed;
                            var completed = new ArrayList<Sample>();
                            try {
                                assertTrue(ready.await(30, TimeUnit.SECONDS), "Workers did not reach the wave barrier");
                                long waveStart = System.nanoTime();
                                release.countDown();
                                for (var future : futures) { completed.add(future.get(60, TimeUnit.SECONDS)); }
                                waveElapsed = System.nanoTime() - waveStart;
                            } finally {
                                release.countDown();
                            }
                            if (!warmup) {
                                measuredWaveNanos += waveElapsed;
                                caseSamples.addAll(completed);
                                for (var sample : completed) {
                                    append(samplesPath, "%s,%d,%d,%d,%d,%d,%d,%d,%s%n".formatted(namespace,
                                            sample.seatCount(), sample.concurrency(), sample.batch(), sample.worker(),
                                            sample.elapsedNanos(), sample.sessionId(), sample.orderId(), sample.requestKey()));
                                }
                            }
                        }
                    }
                    int expectedOrders = (warmupBatches + batches) * concurrency;
                    int expectedHeld = expectedOrders * 2;
                    assertEquals(expectedOrders, jdbc.queryForObject("SELECT COUNT(*) FROM customer_order WHERE session_id=?", Integer.class, session.id()));
                    assertEquals(expectedHeld, jdbc.queryForObject("SELECT COUNT(*) FROM order_item oi JOIN customer_order o ON o.id=oi.order_id WHERE o.session_id=?", Integer.class, session.id()));
                    assertEquals(expectedHeld, jdbc.queryForObject("SELECT COUNT(*) FROM session_seat WHERE session_id=? AND seat_status='HELD' AND current_hold_id IS NOT NULL", Integer.class, session.id()));
                    assertEquals(size - expectedHeld, jdbc.queryForObject("SELECT COUNT(*) FROM session_seat WHERE session_id=? AND seat_status='AVAILABLE' AND current_hold_id IS NULL", Integer.class, session.id()));
                    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM session_seat ss LEFT JOIN seat_hold h ON h.id=ss.current_hold_id WHERE ss.session_id=? AND ss.seat_status='HELD' AND (h.id IS NULL OR h.session_id<>ss.session_id OR h.hold_status<>'ACTIVE')", Integer.class, session.id()));
                    var sorted = caseSamples.stream().mapToLong(Sample::elapsedNanos).sorted().toArray();
                    double meanMs = Arrays.stream(sorted).average().orElseThrow() / 1_000_000d;
                    double medianMs = percentile(sorted, .5) / 1_000_000d;
                    double p95Ms = percentile(sorted, .95) / 1_000_000d;
                    double throughput = caseSamples.size() * 1_000_000_000d / measuredWaveNanos;
                    append(summaryPath, String.format(Locale.ROOT, "%s,%d,%d,%d,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%d%n",
                            namespace, size, concurrency, batches, caseSamples.size(), warmupBatches * concurrency,
                            meanMs, medianMs, p95Ms, measuredWaveNanos / 1_000_000d, throughput, session.id(), expectedHeld));
                    System.out.printf(Locale.ROOT, "HOLD_BENCHMARK seats=%d concurrency=%d measured=%d mean_ms=%.3f p95_ms=%.3f orders_per_s=%.3f held=%d session=%d%n",
                            size, concurrency, caseSamples.size(), meanMs, p95Ms, throughput, expectedHeld, session.id());
                }
            }
            environment.put("status", "COMPLETED");
            environment.put("completedAtUtc", Instant.now().toString());
        } catch (Throwable failure) {
            environment.put("status", "FAILED");
            environment.put("failureType", failure.getClass().getName());
            throw failure;
        } finally {
            saveEnvironment(databasePath, environment);
        }
    }

    private long customerId() {
        var existing = jdbc.queryForList("SELECT id FROM account WHERE login_name='consumer1' AND role='CONSUMER' AND enabled=TRUE", Long.class);
        if (existing.isEmpty()) {
            accounts.seed(TestAuthHelper.PASSWORD);
            existing = jdbc.queryForList("SELECT id FROM account WHERE login_name='consumer1' AND role='CONSUMER' AND enabled=TRUE", Long.class);
        }
        assertEquals(1, existing.size());
        return existing.getFirst();
    }

    private static List<Integer> integers(String property, String defaults) {
        return Arrays.stream(System.getProperty(property, defaults).split(",")).map(Integer::parseInt).distinct().toList();
    }

    private static long percentile(long[] sorted, double quantile) {
        return sorted[(int) Math.ceil(sorted.length * quantile) - 1];
    }

    private static void append(Path path, String line) throws Exception {
        Files.writeString(path, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private void saveEnvironment(Path path, Object value) throws Exception {
        Files.writeString(path, json.writeValueAsString(value), StandardCharsets.UTF_8);
    }

    private record Sample(int seatCount, int concurrency, int batch, int worker, long elapsedNanos,
                          long sessionId, long orderId, String requestKey) { }
}
