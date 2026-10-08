package com.ticketing.catalog;

import com.ticketing.common.web.BusinessException;
import com.ticketing.inventory.InventoryModels.SessionSeat;
import com.ticketing.inventory.InventoryModels.SeatStatus;
import com.ticketing.inventory.InventoryService;
import com.ticketing.inventory.SeatSelectionRules;
import com.ticketing.support.Week2Fixtures;
import com.ticketing.support.TestAuthHelper;
import com.ticketing.identity.DemoAccountSeeder;
import com.ticketing.trade.OrderService;
import com.ticketing.trade.TradeRequests.CreateOrder;
import com.ticketing.trade.TradeModels.SelectionMode;
import com.ticketing.support.Week2Fixtures.Fixture;
import com.ticketing.venue.VenueService;
import com.ticketing.venue.VenueRequests.UpdateArea;
import com.ticketing.venue.VenueRequests.UpdateSeat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.ticketing.catalog.CatalogModels.*;
import static com.ticketing.catalog.CatalogRequests.*;
import static com.ticketing.catalog.SeatQueryModels.*;
import static com.ticketing.support.Week2Fixtures.request;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(SeatQueryIT.FixedClock.class)
class SeatQueryIT {
    private static final Instant NOW = Instant.parse("2026-10-05T04:00:00Z");
    @Autowired private SeatQueryService queries;
    @Autowired private SessionService sessions;
    @Autowired private SessionManagementService management;
    @Autowired private VenueService venues;
    @Autowired private EventService events;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @Autowired private DemoAccountSeeder accounts;
    @Autowired private OrderService orders;
    @MockitoSpyBean private InventoryService inventory;
    @Value("${local.server.port}") private int port;
    private Fixture fixture;
    private long id;

    @BeforeEach
    void prepare() {
        Week2Fixtures.resetTestDatabase(jdbc);
        fixture = Week2Fixtures.create(venues, events);
        id = sessions.create(request("SESSION-W3", fixture)).id();
    }

    @Test
    void completeSeatMapSeparatesInventoryFromSalesEligibility() {
        var draft = queries.seatMap(id);
        assertEquals(NOW, draft.queriedAt());
        assertFalse(draft.reservationMade());
        assertEquals(List.of("FLOOR-A", "BALCONY-B"), draft.areas().stream().map(SeatMapArea::code).toList());
        assertEquals(3, draft.areas().getFirst().rows().size());
        assertEquals(56, seats(draft).size());
        assertEquals(54, seats(draft).stream().filter(s -> s.seatStatus() == SeatStatus.AVAILABLE).count());
        assertTrue(seats(draft).stream().noneMatch(SeatMapSeat::selectable));
        open();
        assertEquals(54, seats(queries.seatMap(id)).stream().filter(SeatMapSeat::selectable).count());
        var a = sessions.listTiers(id).getFirst();
        var before = sessions.listSeats(id);
        management.setTierEnabled(id, a.id(), new SetTierEnabled(false));
        var disabled = queries.seatMap(id);
        assertEquals(20, seats(disabled).stream().filter(SeatMapSeat::selectable).count());
        assertTrue(seats(disabled).stream().filter(s -> a.id().equals(s.priceTierId())).noneMatch(SeatMapSeat::priceTierEnabled));
        assertEquals(before, sessions.listSeats(id));
        management.setSalesStatus(id, new UpdateSalesStatus(SalesStatus.STOPPED));
        assertTrue(seats(queries.seatMap(id)).stream().noneMatch(SeatMapSeat::selectable));
    }

    @Test
    void unavailableSeatWithoutTierRetainsLayoutAndNullPrice() {
        var unavailable = sessions.listSeats(id).stream().filter(s -> s.seatStatus() == SeatStatus.UNAVAILABLE).findFirst().orElseThrow();
        management.configure(id, configuration(List.of(), List.of(new SeatSaleConfiguration(unavailable.id(), null, false))));
        var view = seats(queries.seatMap(id)).stream().filter(s -> s.id() == unavailable.id()).findFirst().orElseThrow();
        assertNull(view.priceTierId());
        assertNull(view.price());
        assertFalse(view.priceTierEnabled());
        assertFalse(view.selectable());
        assertEquals(unavailable.coordinateXSnapshot(), view.coordinateX());
        assertEquals(56, seats(queries.seatMap(id)).size());
    }

    @Test
    void mapAndRankingKeepSnapshotsAfterTemplateChangesAndDeletion() {
        open();
        var beforeMap = queries.seatMap(id);
        var beforeRecommendations = queries.recommend(id, 2, null, null, 20);
        var source = fixture.seat("FLOOR-A", "A", 1);
        venues.updateArea(fixture.floor().id(), new UpdateArea("改名的来源区域", 9));
        venues.updateSeat(source.id(), new UpdateSeat(fixture.floor().id(), "A", 0, 1,
                new BigDecimal("777.125"), new BigDecimal("888.500"), "OTHER", false));
        venues.deleteSeat(source.id());
        assertEquals(beforeMap, queries.seatMap(id));
        assertEquals(beforeRecommendations, queries.recommend(id, 2, null, null, 20));
    }

    @Test
    void recommendationsAreDeterministicLegalReadOnlyAndLimited() {
        open();
        var beforeSession = sessions.get(id);
        var beforeTiers = sessions.listTiers(id);
        var beforeSeats = sessions.listSeats(id);
        var first = queries.recommend(id, 2, null, null, 5);
        assertFalse(first.reservationMade());
        assertEquals(NOW, first.queriedAt());
        assertEquals(5, first.candidates().size());
        assertTrue(first.diagnostics().validCandidateCount() > 5);
        var rules = new SeatSelectionRules();
        for (var candidate : first.candidates()) {
            var ids = candidate.seats().stream().map(SessionSeat::id).toList();
            assertTrue(rules.validate(beforeSeats, ids, true, true).valid());
            var expected = candidate.seats().stream().map(SessionSeat::priceSnapshot).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(0, expected.compareTo(candidate.totalPrice()));
        }
        assertEquals(first, queries.recommend(id, 2, null, null, 5));
        queries.seatMap(id);
        assertEquals(beforeSession, sessions.get(id));
        assertEquals(beforeTiers, sessions.listTiers(id));
        assertEquals(beforeSeats, sessions.listSeats(id));
    }

    @Test
    void filteringDisabledTiersAndInsufficientRunsReturnEmptyWithoutChangingState() {
        open();
        var a = sessions.listTiers(id).getFirst();
        var before = sessions.listSeats(id);
        var filtered = queries.recommend(id, 2, "FLOOR-A", a.id(), 20);
        assertFalse(filtered.candidates().isEmpty());
        assertTrue(filtered.candidates().stream().flatMap(c -> c.seats().stream())
                .allMatch(s -> s.areaCodeSnapshot().equals("FLOOR-A") && s.priceTierId().equals(a.id())));
        management.setTierEnabled(id, a.id(), new SetTierEnabled(false));
        assertTrue(queries.recommend(id, 2, "FLOOR-A", a.id(), 5).candidates().isEmpty());
        var insufficient = queries.recommend(id, Integer.MAX_VALUE, null, null, 5);
        assertTrue(insufficient.candidates().isEmpty());
        assertEquals(insufficient.diagnostics().contiguousRunCount(), insufficient.diagnostics().tooShortRunCount());
        assertEquals(before, sessions.listSeats(id));
    }

    @Test
    void staleCandidateMustBeCheckedAgainstFreshInventory() {
        open();
        var original = queries.recommend(id, 2, "FLOOR-A", null, 5).candidates().getFirst();
        var selected = original.seats().stream().map(SessionSeat::id).toList();
        // Through the real order transaction, HELD always has a valid current holder and history.
        accounts.seed(TestAuthHelper.PASSWORD);
        long consumerId = jdbc.queryForObject("SELECT id FROM account WHERE login_name='consumer1'", Long.class);
        orders.create(consumerId, "w3-stale-candidate", new CreateOrder(id, SelectionMode.RECOMMENDED, selected));
        var fresh = sessions.listSeats(id);
        assertEquals("SEAT_NOT_AVAILABLE", new SeatSelectionRules().validate(fresh, selected, true, true).code());
        var refreshed = queries.recommend(id, 2, "FLOOR-A", null, 20);
        assertTrue(refreshed.candidates().stream().flatMap(c -> c.seats().stream())
                .noneMatch(s -> s.id().equals(selected.getFirst())));
        var held = seats(queries.seatMap(id)).stream().filter(s -> s.id() == selected.getFirst()).findFirst().orElseThrow();
        assertEquals(SeatStatus.HELD, held.seatStatus());
        assertFalse(held.selectable());
    }

    @Test
    void queryReadsOneVersionWhileManagementCommitsWithoutWaitingForReader() throws Exception {
        var originalMap = queries.seatMap(id);
        var tier = sessions.listTiers(id).getFirst();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pauseOnce = new AtomicBoolean(true);
        var target = AopTestUtils.<InventoryService>getUltimateTargetObject(inventory);
        doAnswer(call -> {
            if (pauseOnce.compareAndSet(true, false)) {
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
            }
            return call.callRealMethod();
        }).when(target).listSeats(anyLong());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var read = pool.submit(() -> queries.seatMap(id));
            try {
                if (!entered.await(10, TimeUnit.SECONDS)) {
                    read.get(1, TimeUnit.SECONDS);
                    fail("座位查询未到达协调点");
                }
                var write = pool.submit(() -> {
                    management.configure(id, configuration(List.of(new PriceTierEdit(tier.id(), "新版本 A 档", new BigDecimal("420.00"))), List.of()));
                    return management.setTierEnabled(id, tier.id(), new SetTierEnabled(false));
                });
                assertFalse(write.get(10, TimeUnit.SECONDS).enabled());
            } finally {
                release.countDown();
            }
            assertEquals(originalMap, read.get(10, TimeUnit.SECONDS));
        }
        var next = queries.seatMap(id);
        var changed = seats(next).stream().filter(s -> tier.id().equals(s.priceTierId())).toList();
        assertTrue(changed.stream().allMatch(s -> s.priceTierName().equals("新版本 A 档")
                && s.price().compareTo(new BigDecimal("420.00")) == 0 && !s.priceTierEnabled()));
    }

    @Test
    void httpExposesStructuredMapRecommendationsAndOpenApi() throws Exception {
        open();
        var mapResponse = get("/api/sessions/" + id + "/seat-map");
        var map = data(mapResponse, 200);
        assertEquals(2, map.path("areas").size());
        assertEquals("FLOOR-A", map.path("areas").get(0).path("code").asString());
        assertFalse(map.path("reservationMade").asBoolean());
        var recommendationResponse = get("/api/sessions/" + id + "/recommendations?quantity=2&areaCode=FLOOR-A");
        var recommendations = data(recommendationResponse, 200);
        assertEquals(5, recommendations.path("candidates").size());
        assertEquals(2, recommendations.path("candidates").get(0).path("seats").size());
        assertTrue(recommendations.path("diagnostics").path("newOrphanRejectedCount").asInt() > 0);
        assertEquals(recommendationResponse.headers().firstValue("X-Request-Id").orElseThrow(),
                json.readTree(recommendationResponse.body()).path("requestId").asString());
        var docs = get("/v3/api-docs");
        assertEquals(200, docs.statusCode());
        assertTrue(docs.body().contains("/api/sessions/{id}/seat-map"));
        assertTrue(docs.body().contains("/api/sessions/{id}/recommendations"));
    }

    @Test
    void httpDistinguishesInvalidFiltersSalesConflictsAndNoCandidates() throws Exception {
        var path = "/api/sessions/" + id + "/recommendations";
        assertEquals("SESSION_NOT_ON_SALE", json.readTree(get(path + "?quantity=2").body()).path("code").asString());
        assertEquals(409, get(path + "?quantity=2").statusCode());
        open();
        for (String query : List.of("", "?quantity=0", "?quantity=-1", "?quantity=abc", "?quantity=2&limit=0",
                "?quantity=2&limit=21", "?quantity=2&priceTierId=-1", "?quantity=2&priceTierId=9223372036854775807",
                "?quantity=2&areaCode=UNKNOWN", "?quantity=2&areaCode=")) {
            assertEquals(400, get(path + query).statusCode(), query);
        }
        assertEquals(404, get("/api/sessions/9223372036854775807/seat-map").statusCode());
        assertEquals(404, get("/api/sessions/9223372036854775807/recommendations?quantity=2").statusCode());
        assertEquals(400, get("/api/sessions/-1/seat-map").statusCode());
        assertEquals(0, data(get(path + "?quantity=1000000"), 200).path("candidates").size());
        long other = sessions.create(request("SESSION-OTHER-W3", fixture)).id();
        var foreignTier = sessions.listTiers(other).getFirst();
        assertEquals(400, get(path + "?quantity=2&priceTierId=" + foreignTier.id()).statusCode());
        management.setSalesStatus(id, new UpdateSalesStatus(SalesStatus.STOPPED));
        assertEquals(409, get(path + "?quantity=2").statusCode());
    }

    private void open() { management.setSalesStatus(id, new UpdateSalesStatus(SalesStatus.ON_SALE)); }

    private UpdateSessionConfiguration configuration(List<PriceTierEdit> tiers, List<SeatSaleConfiguration> seats) {
        return new UpdateSessionConfiguration(Week2Fixtures.START, Week2Fixtures.START.minusSeconds(3600),
                Week2Fixtures.START.minusSeconds(300), true, tiers, seats);
    }

    private List<SeatMapSeat> seats(SeatMap map) {
        return map.areas().stream().flatMap(a -> a.rows().stream()).flatMap(r -> r.seats().stream()).toList();
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private JsonNode data(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), response.body());
        return json.readTree(response.body()).path("data");
    }

    @TestConfiguration
    static class FixedClock {
        @Bean @Primary Clock testClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
}
