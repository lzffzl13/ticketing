package com.ticketing.catalog;

import com.ticketing.common.web.BusinessException;
import com.ticketing.inventory.InventoryService;
import com.ticketing.inventory.InventoryModels.SeatStatus;
import com.ticketing.support.Week2Fixtures;
import com.ticketing.support.Week2Fixtures.Fixture;
import com.ticketing.venue.VenueService;
import com.ticketing.venue.VenueRequests.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.ticketing.catalog.CatalogRequests.*;
import static com.ticketing.support.Week2Fixtures.request;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@ActiveProfiles("test")
@Import(SessionSnapshotIT.FixedClock.class)
class SessionSnapshotIT {
    @Autowired private SessionService sessions;
    @Autowired private EventService events;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private VenueService venues;
    @MockitoSpyBean private InventoryService inventory;
    private Fixture fixture;
    private VenueService venueSpyTarget;
    private InventoryService inventorySpyTarget;

    @BeforeEach
    void prepareFixture() {
        // Stub the spy behind the transaction proxy; setting a stub must not invoke MANDATORY advice.
        venueSpyTarget = AopTestUtils.getUltimateTargetObject(venues);
        inventorySpyTarget = AopTestUtils.getUltimateTargetObject(inventory);
        Week2Fixtures.resetTestDatabase(jdbc);
        fixture = Week2Fixtures.create(venues, events);
    }

    @Test
    void createsAll56SeatsWithAislesDisabledSeatsAndSingleSeatPriceOverride() {
        var created = sessions.create(request("SESSION-NORMAL", fixture));
        assertEquals(56, created.seatCount());
        assertEquals(54, created.availableSeatCount());
        assertEquals(2, created.unavailableSeatCount());
        var session = sessions.get(created.id());
        assertEquals(CatalogModels.SalesStatus.DRAFT, session.salesStatus());
        assertNull(session.firstOpenedAt());
        assertEquals(Week2Fixtures.START, session.startsAt());
        assertEquals(Instant.parse("2026-10-04T02:00:00Z"), session.createdAt());
        var seats = sessions.listSeats(created.id());
        var overridden = seats.stream().filter(seat -> seat.sourceSeatTemplateId()
                .equals(fixture.seat("FLOOR-A", "C", 5).id())).findFirst().orElseThrow();
        assertEquals("B 档", overridden.priceTierNameSnapshot());
        assertEquals(new BigDecimal("180.00"), overridden.priceSnapshot());
        assertEquals("L", overridden.aisleSegmentSnapshot());
        assertEquals(2, seats.stream().filter(seat -> seat.seatStatus() == SeatStatus.UNAVAILABLE).count());
        assertEquals(28, seats.stream().filter(seat -> seat.aisleSegmentSnapshot().equals("R")).count());
        assertEquals(2, sessions.listTiers(created.id()).size());
    }

    @Test
    void differentSessionsKeepIndependentTierIdsNamesAndPriceSnapshots() {
        var first = sessions.create(request("SESSION-PRICE-OLD", fixture));
        var before = sessions.listSeats(first.id());
        var base = request("SESSION-PRICE-NEW", fixture);
        var changed = new CreateSession(base.code(), base.eventId(), base.venueId(), base.startsAt(), base.admissionOpenAt(),
                base.admissionCloseAt(), true,
                List.of(new PriceTierInput("TIER-A", "新 A 档", new BigDecimal("480.00")),
                        new PriceTierInput("TIER-B", "新 B 档", new BigDecimal("220.00"))),
                base.areaPrices(), base.seatOverrides());
        var second = sessions.create(changed);
        assertEquals(before, sessions.listSeats(first.id()));
        assertTrue(sessions.listTiers(first.id()).stream().noneMatch(oldTier -> sessions.listTiers(second.id()).stream()
                .anyMatch(newTier -> newTier.id().equals(oldTier.id()))));
        var sourceId = fixture.seat("FLOOR-A", "A", 1).id();
        var freshSeat = sessions.listSeats(second.id()).stream().filter(seat -> seat.sourceSeatTemplateId().equals(sourceId))
                .findFirst().orElseThrow();
        assertEquals(new BigDecimal("480.00"), freshSeat.priceSnapshot());
        assertEquals("新 A 档", freshSeat.priceTierNameSnapshot());
    }

    @Test
    void disabledSeatsCanHaveNoTierWhileEveryEnabledSeatMustHaveOne() {
        var normal = request("SESSION-NO-DISABLED-PRICE", fixture);
        var overrides = fixture.templates().stream().filter(seat -> seat.enabled())
                .map(seat -> new SeatPriceOverride(seat.id(), seat.areaCode().equals("FLOOR-A") ? "TIER-A" : "TIER-B"))
                .toList();
        var onlyEnabledPrices = new CreateSession(normal.code(), normal.eventId(), normal.venueId(), normal.startsAt(),
                normal.admissionOpenAt(), normal.admissionCloseAt(), true, normal.priceTiers(), List.of(), overrides);
        var created = sessions.create(onlyEnabledPrices);
        for (var seat : sessions.listSeats(created.id())) {
            if (seat.seatStatus() == SeatStatus.UNAVAILABLE) {
                assertNull(seat.priceTierId());
                assertNull(seat.priceTierNameSnapshot());
                assertNull(seat.priceSnapshot());
            } else { assertNotNull(seat.priceTierId()); }
        }
    }

    @Test
    void changingVenueAreaAndTemplateDoesNotChangeOldSnapshotAndNewSessionUsesNewValues() {
        var old = sessions.create(request("SESSION-BEFORE-EDIT", fixture));
        var oldSeats = sessions.listSeats(old.id());
        var source = fixture.seat("FLOOR-A", "A", 1);
        venues.updateVenue(fixture.venue().id(), new UpdateVenue("改名场馆", "新地址", true));
        venues.updateArea(fixture.floor().id(), new UpdateArea("改名池座", 4));
        venues.updateSeat(source.id(), new UpdateSeat(fixture.balcony().id(), "ZZ", 9, 77,
                new BigDecimal("111.125"), new BigDecimal("222.500"), "NEW", false));
        assertEquals(oldSeats, sessions.listSeats(old.id()));
        assertEquals("规则测试厅", sessions.get(old.id()).venueNameSnapshot());
        var fresh = sessions.create(request("SESSION-AFTER-EDIT", fixture));
        var moved = sessions.listSeats(fresh.id()).stream().filter(seat -> seat.sourceSeatTemplateId().equals(source.id()))
                .findFirst().orElseThrow();
        assertEquals("改名场馆", sessions.get(fresh.id()).venueNameSnapshot());
        assertEquals("BALCONY-B", moved.areaCodeSnapshot());
        assertEquals("ZZ", moved.rowLabelSnapshot());
        assertEquals(77, moved.seatNumberSnapshot());
        assertEquals(new BigDecimal("111.125"), moved.coordinateXSnapshot());
        assertEquals("NEW", moved.aisleSegmentSnapshot());
        assertEquals(SeatStatus.UNAVAILABLE, moved.seatStatus());
        assertEquals(new BigDecimal("180.00"), moved.priceSnapshot());
        assertTrue(sessions.listSeats(fresh.id()).stream().filter(seat -> seat.areaCodeSnapshot().equals("FLOOR-A"))
                .allMatch(seat -> seat.areaNameSnapshot().equals("改名池座") && seat.areaDisplayOrderSnapshot() == 4));
    }

    @Test
    void deletingTemplateKeepsOldSeatAndRemovesItFromFutureSnapshots() {
        var old = sessions.create(request("SESSION-BEFORE-DELETE", fixture));
        var before = sessions.listSeats(old.id());
        var deleted = fixture.seat("FLOOR-A", "A", 1);
        venues.deleteSeat(deleted.id());
        assertEquals(before, sessions.listSeats(old.id()));
        assertThrows(BusinessException.class, () -> venues.getSeat(deleted.id()));
        var fresh = sessions.create(request("SESSION-AFTER-DELETE", fixture));
        assertEquals(55, fresh.seatCount());
        assertFalse(sessions.listSeats(fresh.id()).stream().anyMatch(seat -> seat.sourceSeatTemplateId().equals(deleted.id())));
    }

    @Test
    void rejectsMissingUnknownAndDuplicateMappingsWithoutLeavingPartialData() {
        var base = request("SESSION-BAD-MAPPING", fixture);
        for (var mappings : List.of(List.<AreaPriceMapping>of(),
                List.of(new AreaPriceMapping(Long.MAX_VALUE, "TIER-A")),
                List.of(new AreaPriceMapping(fixture.floor().id(), "UNKNOWN")),
                List.of(new AreaPriceMapping(fixture.floor().id(), "TIER-A"),
                        new AreaPriceMapping(fixture.floor().id(), "TIER-B")))) {
            var bad = new CreateSession(base.code(), base.eventId(), base.venueId(), base.startsAt(), base.admissionOpenAt(),
                    base.admissionCloseAt(), true, base.priceTiers(), mappings, List.of());
            assertEquals("INVALID_SESSION", assertThrows(BusinessException.class, () -> sessions.create(bad)).getCode());
            assertNoSessionData();
        }
        var unknownOverride = new CreateSession(base.code(), base.eventId(), base.venueId(), base.startsAt(),
                base.admissionOpenAt(), base.admissionCloseAt(), true, base.priceTiers(), base.areaPrices(),
                List.of(new SeatPriceOverride(Long.MAX_VALUE, "TIER-A")));
        assertThrows(BusinessException.class, () -> sessions.create(unknownOverride));
        assertNoSessionData();
    }

    @Test
    void rejectsInvalidWindowAndDisabledSources() {
        var base = request("SESSION-BAD-TIME", fixture);
        var bad = new CreateSession(base.code(), base.eventId(), base.venueId(), base.startsAt(), base.admissionCloseAt(),
                base.admissionOpenAt(), true, base.priceTiers(), base.areaPrices(), base.seatOverrides());
        assertThrows(BusinessException.class, () -> sessions.create(bad));
        venues.updateVenue(fixture.venue().id(), new UpdateVenue("规则测试厅", null, false));
        assertThrows(BusinessException.class, () -> sessions.create(base));
        assertNoSessionData();
    }

    @Test
    void duplicateCodeReturnsConflictAndPreservesOneCompleteSeatSet() {
        var req = request("SESSION-DUPLICATE", fixture);
        var created = sessions.create(req);
        var error = assertThrows(BusinessException.class, () -> sessions.create(req));
        assertEquals("SESSION_CODE_EXISTS", error.getCode());
        assertEquals(409, error.getStatus().value());
        venues.updateVenue(fixture.venue().id(), new UpdateVenue("规则测试厅", null, false));
        assertEquals("SESSION_CODE_EXISTS", assertThrows(BusinessException.class, () -> sessions.create(req)).getCode());
        assertEquals(created.id(), sessions.getByCode(req.code()).id());
        assertEquals(1, count("session"));
        assertEquals(2, count("price_tier"));
        assertEquals(56, count("session_seat"));
    }

    @Test
    void twoConcurrentCreatesWithSameCodeProduceExactlyOneCompleteSession() throws Exception {
        var barrier = new CyclicBarrier(2);
        var req = request("SESSION-CONCURRENT", fixture);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<String> create = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                try { sessions.create(req); return "CREATED"; }
                catch (BusinessException error) { return error.getCode(); }
            };
            var first = executor.submit(create);
            var second = executor.submit(create);
            var results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter("CREATED"::equals).count());
            assertEquals(1, results.stream().filter("SESSION_CODE_EXISTS"::equals).count());
        }
        assertEquals(1, count("session"));
        assertEquals(2, count("price_tier"));
        assertEquals(56, count("session_seat"));
    }

    @Test
    void exceptionAfterActualSnapshotWritesRollsBackSessionTiersAndAllSeats() {
        doAnswer(call -> {
            call.callRealMethod();
            assertEquals(56, count("session_seat"));
            throw new IllegalStateException("test-only fault after inventory writes");
        }).when(inventorySpyTarget).createSnapshots(anyLong(), anyList());
        assertThrows(IllegalStateException.class, () -> sessions.create(request("SESSION-ROLLBACK", fixture)));
        assertNoSessionData();
    }

    @Test
    void sourceEditsCommittedBetweenSourceReadsDoNotMixDatabaseVersions() throws Exception {
        var versionEstablished = new CountDownLatch(1);
        var edited = new CountDownLatch(1);
        var pauseOnce = new AtomicBoolean(true);
        // SessionService has already read the event when it calls readLayout, establishing its read version.
        doAnswer(call -> {
            if (pauseOnce.getAndSet(false)) {
                versionEstablished.countDown();
                assertTrue(edited.await(10, TimeUnit.SECONDS));
            }
            return call.callRealMethod();
        }).when(venueSpyTarget).readLayout(anyLong());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> sessions.create(request("SESSION-OLD-VERSION", fixture)));
            assertTrue(versionEstablished.await(10, TimeUnit.SECONDS));
            try {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    jdbc.update("UPDATE area SET name='并发改名' WHERE id=?", fixture.floor().id());
                    jdbc.update("UPDATE seat_template SET coordinate_x=coordinate_x+100 WHERE area_id=?", fixture.floor().id());
                });
            } finally { edited.countDown(); }
            var old = first.get(15, TimeUnit.SECONDS);
            var oldSeat = sessions.listSeats(old.id()).stream().filter(seat -> seat.sourceSeatTemplateId()
                    .equals(fixture.seat("FLOOR-A", "A", 1).id())).findFirst().orElseThrow();
            assertEquals("池座", oldSeat.areaNameSnapshot());
            assertEquals(new BigDecimal("1.000"), oldSeat.coordinateXSnapshot());
        } finally { edited.countDown(); }
        var next = sessions.create(request("SESSION-NEW-VERSION", fixture));
        var newSeat = sessions.listSeats(next.id()).stream().filter(seat -> seat.sourceSeatTemplateId()
                .equals(fixture.seat("FLOOR-A", "A", 1).id())).findFirst().orElseThrow();
        assertEquals("并发改名", newSeat.areaNameSnapshot());
        assertEquals(new BigDecimal("101.000"), newSeat.coordinateXSnapshot());
    }

    @Test
    void databaseRejectsDuplicateSnapshotsCrossSessionTiersAndIncompletePriceData() {
        var first = sessions.create(request("SESSION-CONSTRAINT-A", fixture));
        var second = sessions.create(request("SESSION-CONSTRAINT-B", fixture));
        var seat = sessions.listSeats(first.id()).stream().filter(s -> s.seatStatus() == SeatStatus.AVAILABLE).findFirst().orElseThrow();
        long foreignTier = sessions.listTiers(second.id()).getFirst().id();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE session_seat SET price_tier_id=? WHERE id=?", foreignTier, seat.id()));
        assertPriceCheckRejected(() -> jdbc.update(
                "UPDATE session_seat SET price_snapshot=NULL WHERE id=?", seat.id()));
        assertPriceCheckRejected(() -> jdbc.update(
                "UPDATE session_seat SET price_tier_id=NULL, price_tier_name_snapshot=NULL, price_snapshot=NULL WHERE id=?", seat.id()));
        assertPriceCheckRejected(() -> jdbc.update(
                "UPDATE session_seat SET price_snapshot=0 WHERE id=?", seat.id()));
        var another = sessions.listSeats(first.id()).getLast();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE session_seat SET source_seat_template_id=? WHERE id=?", seat.sourceSeatTemplateId(), another.id()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM venue WHERE id=?", fixture.venue().id()));
        assertEquals(112, count("session_seat"));
    }

    @Test
    void batchTemplateInsertIsAtomicAndValidatesRowOrdering() {
        var duplicate = new SeatInput("Z", 99, 1, BigDecimal.ZERO, BigDecimal.ZERO, "L", true);
        assertThrows(DataIntegrityViolationException.class, () -> venues.addSeats(fixture.floor().id(), List.of(duplicate, duplicate)));
        assertEquals(56, count("seat_template"));
        var wrongRow = new SeatInput("A", 99, 20, BigDecimal.ZERO, BigDecimal.ZERO, "L", true);
        assertThrows(BusinessException.class, () -> venues.addSeats(fixture.floor().id(), List.of(wrongRow)));
        var wrongOrder = new SeatInput("Z", 0, 20, BigDecimal.ZERO, BigDecimal.ZERO, "L", true);
        assertThrows(BusinessException.class, () -> venues.addSeats(fixture.floor().id(), List.of(wrongOrder)));
        assertEquals(56, count("seat_template"));
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    private void assertPriceCheckRejected(Runnable write) {
        var error = assertThrows(UncategorizedSQLException.class, write::run);
        assertEquals(3819, error.getSQLException().getErrorCode());
        assertTrue(error.getSQLException().getMessage().contains("ck_snapshot_price"));
    }

    private void assertNoSessionData() {
        assertEquals(0, count("session"));
        assertEquals(0, count("price_tier"));
        assertEquals(0, count("session_seat"));
    }

    @TestConfiguration
    static class FixedClock {
        @Bean @Primary Clock testClock() {
            return Clock.fixed(Instant.parse("2026-10-04T02:00:00Z"), ZoneOffset.UTC);
        }
    }
}
