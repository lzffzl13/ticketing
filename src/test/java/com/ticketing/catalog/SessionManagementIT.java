package com.ticketing.catalog;

import com.ticketing.common.web.BusinessException;
import com.ticketing.inventory.InventoryService;
import com.ticketing.inventory.InventoryModels.SessionSeat;
import com.ticketing.inventory.InventoryModels.SeatStatus;
import com.ticketing.support.Week2Fixtures;
import com.ticketing.support.Week2Fixtures.Fixture;
import com.ticketing.venue.VenueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.mybatis.spring.SqlSessionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.ticketing.catalog.CatalogModels.*;
import static com.ticketing.catalog.CatalogRequests.*;
import static com.ticketing.support.Week2Fixtures.request;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@ActiveProfiles("test")
@Import(SessionManagementIT.TestClock.class)
class SessionManagementIT {
    @Autowired private SessionService sessions;
    @Autowired private SessionManagementService management;
    @Autowired private VenueService venues;
    @Autowired private EventService events;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private SqlSessionTemplate sqlSession;
    @MockitoSpyBean private CatalogMapper mapper;
    @MockitoSpyBean private InventoryService inventory;
    private InventoryService inventorySpyTarget;
    private Fixture fixture;
    private long id;
    private PriceTier tier;

    @BeforeEach
    void prepare() {
        clock.set(Instant.parse("2026-10-05T02:00:00Z"));
        inventorySpyTarget = AopTestUtils.getUltimateTargetObject(inventory);
        Week2Fixtures.resetTestDatabase(jdbc);
        fixture = Week2Fixtures.create(venues, events);
        id = sessions.create(request("SESSION-MANAGEMENT", fixture)).id();
        tier = sessions.listTiers(id).getFirst();
    }

    @Test
    void draftConfigurationUpdatesAllLinkedPricesAndTimesWithoutTouchingLayoutOrOtherSession() {
        long other = sessions.create(request("SESSION-OTHER", fixture)).id();
        var otherSeats = sessions.listSeats(other);
        var templates = venues.listLayoutSeats(fixture.venue().id());
        var before = sessions.listSeats(id);
        var movedSeat = before.stream().filter(s -> !tier.id().equals(s.priceTierId())).findFirst().orElseThrow();
        var changed = management.configure(id, configuration(
                List.of(new PriceTierEdit(tier.id(), "调整后 A 档", new BigDecimal("420.00"))),
                List.of(new SeatSaleConfiguration(movedSeat.id(), tier.id(), true))));
        assertEquals(Week2Fixtures.START.plusSeconds(3600), changed.startsAt());
        assertFalse(changed.forbidNewOrphan());
        assertEquals(SalesStatus.DRAFT, changed.salesStatus());
        assertNull(changed.firstOpenedAt());
        var after = sessions.listSeats(id);
        assertEquals(56, after.size());
        for (int i = 0; i < before.size(); i++) {
            var oldSeat = before.get(i);
            var seat = after.get(i);
            assertEquals(oldSeat.id(), seat.id());
            assertEquals(oldSeat.sourceSeatTemplateId(), seat.sourceSeatTemplateId());
            assertEquals(oldSeat.areaNameSnapshot(), seat.areaNameSnapshot());
            assertEquals(oldSeat.rowLabelSnapshot(), seat.rowLabelSnapshot());
            assertEquals(oldSeat.coordinateXSnapshot(), seat.coordinateXSnapshot());
            assertEquals(oldSeat.aisleSegmentSnapshot(), seat.aisleSegmentSnapshot());
            if (tier.id().equals(seat.priceTierId())) {
                assertEquals("调整后 A 档", seat.priceTierNameSnapshot());
                assertEquals(new BigDecimal("420.00"), seat.priceSnapshot());
            }
        }
        // 不可售的 C1 原本也有 A 档；价格同步不能漏掉它。
        assertEquals(SeatStatus.UNAVAILABLE, seat("FLOOR-A", "C", 1).seatStatus());
        assertEquals(new BigDecimal("420.00"), seat("FLOOR-A", "C", 1).priceSnapshot());
        assertEquals(otherSeats, sessions.listSeats(other));
        assertEquals(templates, venues.listLayoutSeats(fixture.venue().id()));
    }

    @Test
    void unavailableSeatCanClearPricesButRequiresTierBeforeBecomingAvailable() {
        var disabled = seat("FLOOR-A", "C", 1);
        management.configure(id, configuration(List.of(), List.of(new SeatSaleConfiguration(disabled.id(), null, false))));
        assertNull(seat("FLOOR-A", "C", 1).priceTierId());
        assertNull(seat("FLOOR-A", "C", 1).priceSnapshot());
        var error = assertThrows(BusinessException.class, () -> management.configure(id,
                configuration(List.of(), List.of(new SeatSaleConfiguration(disabled.id(), null, true)))));
        assertEquals(400, error.getStatus().value());
        management.configure(id, configuration(List.of(), List.of(new SeatSaleConfiguration(disabled.id(), tier.id(), true))));
        var enabled = seat("FLOOR-A", "C", 1);
        assertEquals(SeatStatus.AVAILABLE, enabled.seatStatus());
        assertEquals(new BigDecimal("380.00"), enabled.priceSnapshot());
        assertEquals(55, sessions.listSeats(id).stream().filter(s -> s.seatStatus() == SeatStatus.AVAILABLE).count());
    }

    @Test
    void rejectsForeignUnknownAndDuplicateIdsAndInvalidTimesWithNoPartialUpdates() {
        long other = sessions.create(request("SESSION-FOREIGN", fixture)).id();
        long foreignTier = sessions.listTiers(other).getFirst().id();
        long foreignSeat = sessions.listSeats(other).getFirst().id();
        var edit = new PriceTierEdit(tier.id(), "不应保存", new BigDecimal("450.00"));
        var seat = sessions.listSeats(id).getFirst();
        var beforeSession = sessions.get(id);
        var beforeTiers = sessions.listTiers(id);
        var beforeSeats = sessions.listSeats(id);
        for (var bad : List.of(
                configuration(List.of(new PriceTierEdit(foreignTier, "外部票档", BigDecimal.ONE)), List.of()),
                configuration(List.of(edit, edit), List.of()),
                configuration(List.of(edit), List.of(new SeatSaleConfiguration(foreignSeat, tier.id(), true))),
                configuration(List.of(edit), List.of(new SeatSaleConfiguration(Long.MAX_VALUE, tier.id(), true))),
                configuration(List.of(edit), List.of(new SeatSaleConfiguration(seat.id(), foreignTier, true))),
                configuration(List.of(edit), List.of(new SeatSaleConfiguration(seat.id(), tier.id(), true),
                        new SeatSaleConfiguration(seat.id(), tier.id(), false))),
                new UpdateSessionConfiguration(Week2Fixtures.START, Week2Fixtures.START, Week2Fixtures.START,
                        true, List.of(edit), List.of()))) {
            assertEquals(400, assertThrows(BusinessException.class, () -> management.configure(id, bad)).getStatus().value());
            assertEquals(beforeSession, sessions.get(id));
            assertEquals(beforeTiers, sessions.listTiers(id));
            assertEquals(beforeSeats, sessions.listSeats(id));
        }
    }

    @Test
    void opensPausesResumesAndRepeatsWithoutRewritingFirstOpenedTime() {
        var opened = management.setSalesStatus(id, new UpdateSalesStatus(SalesStatus.ON_SALE));
        var firstOpened = opened.firstOpenedAt();
        assertEquals(clock.instant(), firstOpened);
        assertEquals(SalesStatus.ON_SALE, opened.salesStatus());
        clock.set(clock.instant().plusSeconds(60));
        assertEquals(opened, management.setSalesStatus(id, new UpdateSalesStatus(SalesStatus.ON_SALE)));
        var paused = management.setSalesStatus(id, new UpdateSalesStatus(SalesStatus.STOPPED));
        assertEquals(SalesStatus.STOPPED, paused.salesStatus());
        assertEquals(firstOpened, paused.firstOpenedAt());
        clock.set(clock.instant().plusSeconds(60));
        assertEquals(paused, management.setSalesStatus(id, new UpdateSalesStatus(SalesStatus.STOPPED)));
        var resumed = management.setSalesStatus(id, new UpdateSalesStatus(SalesStatus.ON_SALE));
        assertEquals(SalesStatus.ON_SALE, resumed.salesStatus());
        assertEquals(firstOpened, resumed.firstOpenedAt());
        assertEquals(clock.instant(), resumed.updatedAt());
    }

    @Test
    void invalidSalesTransitionsNeverChangeState() {
        assertEquals("INVALID_SALES_TRANSITION", assertThrows(BusinessException.class,
                () -> management.setSalesStatus(id, new UpdateSalesStatus(SalesStatus.STOPPED))).getCode());
        assertNull(sessions.get(id).firstOpenedAt());
        management.setSalesStatus(id, new UpdateSalesStatus(SalesStatus.ON_SALE));
        var opened = sessions.get(id);
        assertEquals("INVALID_SALES_TRANSITION", assertThrows(BusinessException.class,
                () -> management.setSalesStatus(id, new UpdateSalesStatus(SalesStatus.DRAFT))).getCode());
        assertEquals(opened, sessions.get(id));
    }

    @Test
    void openedAndPausedSessionsBothRejectConfigurationEdits() {
        var before = sessions.listSeats(id);
        var edits = configuration(List.of(new PriceTierEdit(tier.id(), "不能改价", new BigDecimal("500.00"))), List.of());
        management.setSalesStatus(id, new UpdateSalesStatus(SalesStatus.ON_SALE));
        assertEquals("SESSION_CONFIGURATION_FROZEN", assertThrows(BusinessException.class,
                () -> management.configure(id, edits)).getCode());
        management.setSalesStatus(id, new UpdateSalesStatus(SalesStatus.STOPPED));
        assertEquals("SESSION_CONFIGURATION_FROZEN", assertThrows(BusinessException.class,
                () -> management.configure(id, edits)).getCode());
        assertEquals(before, sessions.listSeats(id));
        assertEquals(new BigDecimal("380.00"), sessions.listTiers(id).getFirst().price());
    }

    @Test
    void tierSwitchesChangeOnlyTheLiveSwitchAndReadinessIsCheckedOnOpening() {
        var before = sessions.listSeats(id);
        for (var priceTier : sessions.listTiers(id)) {
            management.setTierEnabled(id, priceTier.id(), new SetTierEnabled(false));
        }
        assertEquals("SESSION_NOT_SALEABLE", assertThrows(BusinessException.class,
                () -> management.setSalesStatus(id, new UpdateSalesStatus(SalesStatus.ON_SALE))).getCode());
        assertNull(sessions.get(id).firstOpenedAt());
        management.setTierEnabled(id, tier.id(), new SetTierEnabled(true));
        management.setSalesStatus(id, new UpdateSalesStatus(SalesStatus.ON_SALE));
        var disabled = management.setTierEnabled(id, tier.id(), new SetTierEnabled(false));
        clock.set(clock.instant().plusSeconds(60));
        assertEquals(disabled, management.setTierEnabled(id, tier.id(), new SetTierEnabled(false)));
        // 相同 ON_SALE 目标直接返回，不把重复设置误当成新的开售。
        assertEquals(SalesStatus.ON_SALE, management.setSalesStatus(id, new UpdateSalesStatus(SalesStatus.ON_SALE)).salesStatus());
        management.setSalesStatus(id, new UpdateSalesStatus(SalesStatus.STOPPED));
        assertEquals("SESSION_NOT_SALEABLE", assertThrows(BusinessException.class,
                () -> management.setSalesStatus(id, new UpdateSalesStatus(SalesStatus.ON_SALE))).getCode());
        assertEquals(before, sessions.listSeats(id));
    }

    @Test
    void allUnavailableSeatsPreventOpeningEvenIfTiersAreEnabled() {
        var changes = sessions.listSeats(id).stream().map(s -> new SeatSaleConfiguration(s.id(), s.priceTierId(), false)).toList();
        management.configure(id, configuration(List.of(), changes));
        assertEquals("SESSION_NOT_SALEABLE", assertThrows(BusinessException.class,
                () -> management.setSalesStatus(id, new UpdateSalesStatus(SalesStatus.ON_SALE))).getCode());
        assertEquals(SalesStatus.DRAFT, sessions.get(id).salesStatus());
    }

    @Test
    void faultAfterActualConfigurationWritesRollsBackSessionTiersAndSeats() {
        var sessionBefore = sessions.get(id);
        var tiersBefore = sessions.listTiers(id);
        var seatsBefore = sessions.listSeats(id);
        var disabled = seat("FLOOR-A", "C", 1);
        doAnswer(call -> {
            call.callRealMethod();
            assertEquals(new BigDecimal("420.00"), seat("FLOOR-A", "A", 1).priceSnapshot());
            assertEquals(SeatStatus.AVAILABLE, seat("FLOOR-A", "C", 1).seatStatus());
            throw new IllegalStateException("test-only fault after actual configuration writes");
        }).when(inventorySpyTarget).configureSeats(anyLong(), anyList(), any());
        assertThrows(IllegalStateException.class, () -> management.configure(id, configuration(
                List.of(new PriceTierEdit(tier.id(), "应回滚", new BigDecimal("420.00"))),
                List.of(new SeatSaleConfiguration(disabled.id(), tier.id(), true)))));
        assertEquals(sessionBefore, sessions.get(id));
        assertEquals(tiersBefore, sessions.listTiers(id));
        assertEquals(seatsBefore, sessions.listSeats(id));
    }

    @Test
    void configurationHoldingLockFinishesBeforeConcurrentOpening() throws Exception {
        var edits = configuration(List.of(new PriceTierEdit(tier.id(), "先改价", new BigDecimal("420.00"))), List.of());
        runWithFirstOperationHoldingLock(() -> { management.configure(id, edits); return "CONFIGURED"; },
                () -> { management.setSalesStatus(id, new UpdateSalesStatus(SalesStatus.ON_SALE)); return "OPENED"; },
                "CONFIGURED", "OPENED");
        assertEquals(SalesStatus.ON_SALE, sessions.get(id).salesStatus());
        assertEquals(new BigDecimal("420.00"), seat("FLOOR-A", "A", 1).priceSnapshot());
    }

    @Test
    void openingHoldingLockMakesWaitingConfigurationSeeFrozenState() throws Exception {
        var edits = configuration(List.of(new PriceTierEdit(tier.id(), "不应写入", new BigDecimal("420.00"))), List.of());
        runWithFirstOperationHoldingLock(
                () -> { management.setSalesStatus(id, new UpdateSalesStatus(SalesStatus.ON_SALE)); return "OPENED"; },
                () -> {
                    try { management.configure(id, edits); return "CONFIGURED"; }
                    catch (BusinessException error) { return error.getCode(); }
                }, "OPENED", "SESSION_CONFIGURATION_FROZEN");
        assertEquals(SalesStatus.ON_SALE, sessions.get(id).salesStatus());
        assertEquals(new BigDecimal("380.00"), seat("FLOOR-A", "A", 1).priceSnapshot());
    }

    private void runWithFirstOperationHoldingLock(Callable<String> firstOperation, Callable<String> secondOperation,
                                                  String firstResult, String secondResult) throws Exception {
        var locked = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var firstCall = new AtomicBoolean(true);
        doAnswer(call -> {
            if (firstCall.compareAndSet(true, false)) {
                // Mapper 是接口代理，不能 callRealMethod；显式委托真实 MyBatis Mapper，仍参与本线程事务。
                var result = sqlSession.getMapper(CatalogMapper.class).lockSession(id);
                locked.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return result;
            }
            secondEntered.countDown();
            return sqlSession.getMapper(CatalogMapper.class).lockSession(id); // 第二个真实事务等待同一行锁。
        }).when(mapper).lockSession(id);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(firstOperation);
            if (!locked.await(10, TimeUnit.SECONDS)) {
                first.get(1, TimeUnit.SECONDS); // 优先报告工作线程的真实异常，避免只剩信号超时。
                fail("First operation did not signal a real held lock");
            }
            var second = executor.submit(secondOperation);
            try { assertTrue(secondEntered.await(10, TimeUnit.SECONDS)); }
            finally { release.countDown(); }
            assertEquals(firstResult, first.get(15, TimeUnit.SECONDS));
            assertEquals(secondResult, second.get(15, TimeUnit.SECONDS));
        } finally { release.countDown(); }
    }

    private UpdateSessionConfiguration configuration(List<PriceTierEdit> tiers, List<SeatSaleConfiguration> seats) {
        var start = Week2Fixtures.START.plusSeconds(3600);
        return new UpdateSessionConfiguration(start, start.minusSeconds(3600), start.minusSeconds(300), false, tiers, seats);
    }

    private SessionSeat seat(String area, String row, int number) {
        return sessions.listSeats(id).stream().filter(s -> s.areaCodeSnapshot().equals(area)
                && s.rowLabelSnapshot().equals(row) && s.seatNumberSnapshot() == number).findFirst().orElseThrow();
    }

    @TestConfiguration
    static class TestClock {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }

    static class MutableClock extends Clock {
        private volatile Instant instant = Instant.parse("2026-10-05T02:00:00Z");
        void set(Instant at) { instant = at; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) {
            if (!zone.equals(ZoneOffset.UTC)) { throw new IllegalArgumentException("Test clock uses UTC"); }
            return this;
        }
        @Override public Instant instant() { return instant; }
    }
}
