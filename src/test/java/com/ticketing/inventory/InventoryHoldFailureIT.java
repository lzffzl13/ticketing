package com.ticketing.inventory;

import com.ticketing.catalog.EventService;
import com.ticketing.catalog.SessionManagementService;
import com.ticketing.catalog.SessionService;
import com.ticketing.identity.DemoAccountSeeder;
import com.ticketing.support.TestAuthHelper;
import com.ticketing.support.Week2Fixtures;
import com.ticketing.trade.OrderService;
import com.ticketing.trade.TradeModels.SelectionMode;
import com.ticketing.trade.TradeRequests.CreateOrder;
import com.ticketing.venue.VenueService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.ticketing.catalog.CatalogModels.SalesStatus.ON_SALE;
import static com.ticketing.catalog.CatalogRequests.UpdateSalesStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@ActiveProfiles("test")
@Import(InventoryHoldFailureIT.FixedClock.class)
class InventoryHoldFailureIT {
    @Autowired private OrderService orders;
    @Autowired private SessionService sessions;
    @Autowired private SessionManagementService management;
    @Autowired private VenueService venues;
    @Autowired private EventService events;
    @Autowired private DemoAccountSeeder accounts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SqlSessionTemplate sqlSession;
    @MockitoSpyBean private InventoryHoldMapper mapper;
    private Week2Fixtures.Fixture fixture;
    private long id;
    private long customer;
    private List<Long> selected;

    @BeforeEach
    void prepare() {
        Week2Fixtures.resetTestDatabase(jdbc);
        accounts.seed(TestAuthHelper.PASSWORD);
        customer = jdbc.queryForObject("SELECT id FROM account WHERE login_name='consumer1'", Long.class);
        fixture = Week2Fixtures.create(venues, events);
        id = sessions.create(Week2Fixtures.request("SESSION-HOLD-FAILURE", fixture)).id();
        management.setSalesStatus(id, new UpdateSalesStatus(ON_SALE));
        selected = sessions.listSeats(id).stream().filter(s -> s.areaCodeSnapshot().equals("FLOOR-A")
                && s.rowLabelSnapshot().equals("A") && s.seatNumberSnapshot() <= 2).map(InventoryModels.SessionSeat::id).toList();
    }

    @Test
    void exceptionAfterActualFirstSeatUpdateRollsBackThePartiallyWrittenHoldAndOrder() {
        doAnswer(call -> {
            int changed = sqlSession.getMapper(InventoryHoldMapper.class).holdSeat(call.getArgument(0),
                    call.getArgument(1), call.getArgument(2), call.getArgument(3));
            assertEquals(1, changed);
            assertEquals(1, count("seat_hold"));
            assertEquals(1, count("seat_hold_item"));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM session_seat WHERE seat_status='HELD'", Integer.class));
            throw new IllegalStateException("test fault after the first real seat update");
        }).when(mapper).holdSeat(anyLong(), anyLong(), anyLong(), any());
        assertThrows(IllegalStateException.class, this::create);
        assertCleanRollback();
    }

    @Test
    void exceptionAfterActualFirstHoldDetailRollsBackBeforeAnySeatBecomesHeld() {
        doAnswer(call -> {
            int changed = sqlSession.getMapper(InventoryHoldMapper.class).insertItem(call.getArgument(0),
                    call.getArgument(1), call.getArgument(2), call.getArgument(3));
            assertEquals(1, changed);
            assertEquals(1, count("seat_hold_item"));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM session_seat WHERE seat_status='HELD'", Integer.class));
            throw new IllegalStateException("test fault after a real hold detail write");
        }).when(mapper).insertItem(anyLong(), anyLong(), anyLong(), any());
        assertThrows(IllegalStateException.class, this::create);
        assertCleanRollback();
    }

    @Test
    void databaseRejectsHeldWithoutOwnerAndAvailableWithOwner() {
        assertSqlCode(3819, () -> jdbc.update("UPDATE session_seat SET seat_status='HELD' WHERE id=?", selected.getFirst()));
        var order = create();
        assertSqlCode(3819, () -> jdbc.update("UPDATE session_seat SET current_hold_id=NULL WHERE id=?", selected.getFirst()));
        assertSqlCode(3819, () -> jdbc.update("UPDATE session_seat SET seat_status='AVAILABLE' WHERE id=?", selected.getFirst()));
        long available = sessions.listSeats(id).stream().filter(s -> s.seatStatus() == InventoryModels.SeatStatus.AVAILABLE)
                .findFirst().orElseThrow().id();
        assertSqlCode(3819, () -> jdbc.update("UPDATE session_seat SET current_hold_id=? WHERE id=?", order.hold().id(), available));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM session_seat WHERE current_hold_id=?", Integer.class, order.hold().id()));
    }

    @Test
    void databaseRejectsCrossSessionCurrentHolderAndHoldDetails() {
        var order = create();
        long other = sessions.create(Week2Fixtures.request("SESSION-HOLD-FOREIGN", fixture)).id();
        long seat = sessions.listSeats(other).getFirst().id();
        assertSqlCode(1452, () -> jdbc.update("UPDATE session_seat SET seat_status='HELD',current_hold_id=? WHERE id=?",
                order.hold().id(), seat));
        assertSqlCode(1452, () -> jdbc.update("""
                INSERT INTO seat_hold_item (hold_id,session_id,session_seat_id,created_at)
                VALUES (?,?,?,?)
                """, order.hold().id(), other, seat, java.sql.Timestamp.from(Instant.parse("2026-10-08T02:00:00Z"))));
        assertEquals(2, count("seat_hold_item"));
        assertEquals(InventoryModels.SeatStatus.AVAILABLE, sessions.listSeats(other).getFirst().seatStatus());
    }

    private com.ticketing.trade.TradeModels.OrderDetails create() {
        return orders.create(customer, "hold-fault", new CreateOrder(id, SelectionMode.RECOMMENDED, selected)).order();
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private void assertCleanRollback() {
        for (String table : List.of("customer_order", "order_item", "seat_hold", "seat_hold_item")) { assertEquals(0, count(table), table); }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM session_seat WHERE current_hold_id IS NOT NULL OR seat_status='HELD'", Integer.class));
    }
    private void assertSqlCode(int code, Runnable mutation) {
        var error = assertThrows(DataAccessException.class, mutation::run);
        assertInstanceOf(SQLException.class, error.getMostSpecificCause());
        assertEquals(code, ((SQLException) error.getMostSpecificCause()).getErrorCode());
    }
    @TestConfiguration
    static class FixedClock {
        @Bean @Primary Clock holdFailureClock() { return Clock.fixed(Instant.parse("2026-10-08T02:00:00Z"), ZoneOffset.UTC); }
    }
}
