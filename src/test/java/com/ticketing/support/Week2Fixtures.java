package com.ticketing.support;

import com.ticketing.catalog.EventService;
import com.ticketing.catalog.CatalogModels.Event;
import com.ticketing.catalog.CatalogRequests.*;
import com.ticketing.venue.VenueService;
import com.ticketing.venue.VenueModels.*;
import com.ticketing.venue.VenueRequests.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class Week2Fixtures {
    public static final Instant START = Instant.parse("2027-01-15T12:00:00Z");

    private Week2Fixtures() { }

    public static void resetTestDatabase(JdbcTemplate jdbc) {
        assertEquals("ticketing_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        // Explicit child-first cleanup of the dedicated test database; no Flyway clean or FK bypass.
        for (String table : List.of("checkin_record", "simulator_refund_notification", "simulator_refund", "refund_record", "simulated_notification", "simulated_payment", "ticket", "payment_record", "order_item", "seat_hold_item", "session_seat", "seat_hold", "customer_order",
                "price_tier", "session", "seat_template", "area", "event", "venue", "demo_login_token", "account")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    public static Fixture create(VenueService venues, EventService events) {
        var venue = venues.createVenue(new CreateVenue("VENUE-RULES", "规则测试厅", "虚拟地址", true));
        var floor = venues.createArea(venue.id(), new CreateArea("FLOOR-A", "池座", 0));
        var balcony = venues.createArea(venue.id(), new CreateArea("BALCONY-B", "楼座", 1));
        venues.addSeats(floor.id(), seats("ABC", 12, 6, "C", 1));
        venues.addSeats(balcony.id(), seats("DE", 10, 5, "E", 10));
        var event = events.create(new CreateEvent("EVENT-STARLIGHT", "星河交响夜", "虚拟演出", true));
        return new Fixture(venue, floor, balcony, event, venues.listLayoutSeats(venue.id()));
    }

    private static List<SeatInput> seats(String labels, int count, int aisle, String disabledRow, int disabledNumber) {
        var seats = new ArrayList<SeatInput>();
        for (int row = 0; row < labels.length(); row++) {
            String label = String.valueOf(labels.charAt(row));
            for (int number = 1; number <= count; number++) {
                seats.add(new SeatInput(label, row, number, BigDecimal.valueOf(number), BigDecimal.valueOf(row),
                        number <= aisle ? "L" : "R", !(label.equals(disabledRow) && number == disabledNumber)));
            }
        }
        return seats;
    }

    public static CreateSession request(String code, Fixture fixture) {
        return new CreateSession(code, fixture.event.id(), fixture.venue.id(), START, START.minusSeconds(3600),
                START.minusSeconds(300), true,
                List.of(new PriceTierInput("TIER-A", "A 档", new BigDecimal("380.00")),
                        new PriceTierInput("TIER-B", "B 档", new BigDecimal("180.00"))),
                List.of(new AreaPriceMapping(fixture.floor.id(), "TIER-A"),
                        new AreaPriceMapping(fixture.balcony.id(), "TIER-B")),
                List.of(new SeatPriceOverride(fixture.seat("FLOOR-A", "C", 5).id(), "TIER-B")));
    }

    public record Fixture(Venue venue, Area floor, Area balcony, Event event, List<LayoutSeat> templates) {
        public LayoutSeat seat(String area, String row, int number) {
            return templates.stream().filter(seat -> seat.areaCode().equals(area) && seat.rowLabel().equals(row)
                    && seat.seatNumber() == number).findFirst().orElseThrow();
        }
    }
}
