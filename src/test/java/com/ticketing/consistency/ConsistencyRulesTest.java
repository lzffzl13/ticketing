package com.ticketing.consistency;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static com.ticketing.consistency.ConsistencySnapshot.*;
import static org.junit.jupiter.api.Assertions.*;

class ConsistencyRulesTest {
    private static final Instant EXPIRY = Instant.parse("2026-10-18T10:15:00Z");
    private static final BigDecimal PRICE = new BigDecimal("100.00");
    private final ConsistencyRules rules = new ConsistencyRules();

    @Test
    void normalPendingAndPaidSnapshotsHaveNoDifferences() {
        var pending = fixture();
        pending.orders = List.of(order("PENDING_PAYMENT"));
        pending.holds = List.of(new Hold(3, 1, "ACTIVE", EXPIRY));
        pending.payments = List.of(new Payment(4, 1, "PROCESSING", PRICE, EXPIRY));
        pending.seats = List.of(new Seat(6, "HELD", 3L));
        pending.tickets = List.of();
        assertTrue(check(pending).isEmpty());
        assertTrue(check(fixture()).isEmpty());
    }

    @Test
    void cancelledHistoryDoesNotClaimThatTheOldSeatMustRemainAvailable() {
        var data = fixture();
        data.orders = List.of(order("CANCELLED"), new Order(9, "PENDING_PAYMENT", PRICE, EXPIRY, false));
        data.items = List.of(new Item(2, 1, 6, PRICE), new Item(10, 9, 6, PRICE));
        data.holds = List.of(new Hold(3, 1, "RELEASED", EXPIRY), new Hold(11, 9, "ACTIVE", EXPIRY));
        data.holdItems = List.of(new HoldItem(3, 6), new HoldItem(11, 6));
        data.payments = List.of(new Payment(4, 1, "CLOSED", PRICE, EXPIRY));
        data.tickets = List.of();
        data.seats = List.of(new Seat(6, "HELD", 11L));
        assertTrue(check(data).isEmpty());
    }

    @Test
    void successfulRefundHistoryDoesNotClaimTheResoldSeatOrReviveItsOldTicket() {
        var data = fixture();
        data.orders = List.of(order("FULLY_REFUNDED"), new Order(9, "PAID", PRICE, EXPIRY, false));
        data.items = List.of(new Item(2, 1, 6, PRICE), new Item(10, 9, 6, PRICE));
        data.holds = List.of(new Hold(3, 1, "CONSUMED", EXPIRY), new Hold(11, 9, "CONSUMED", EXPIRY));
        data.holdItems = List.of(new HoldItem(3, 6), new HoldItem(11, 6));
        data.payments = List.of(new Payment(4, 1, "SUCCEEDED", PRICE, EXPIRY), new Payment(12, 9, "SUCCEEDED", PRICE, EXPIRY));
        data.tickets = List.of(ticket("REFUNDED"), new Ticket(13, 9, 10, 6, "VALID"));
        data.refunds = List.of(refund("SUCCEEDED", PRICE));
        assertTrue(check(data).isEmpty());
    }

    @Test
    void failedRefundHistoryAllowsLaterRetryAndCheckin() {
        var data = fixture();
        data.orders = List.of(order("COMPLETED"));
        data.refunds = List.of(refund("FAILED", PRICE));
        data.tickets = List.of(ticket("CHECKED_IN"));
        data.checkins = List.of(new Checkin(8, 5L, 7L, 7));
        assertTrue(check(data).isEmpty());
        data.orders = List.of(order("FULLY_REFUNDED"));
        data.tickets = List.of(ticket("REFUNDED"));
        data.checkins = List.of();
        data.seats = List.of(new Seat(6, "AVAILABLE", null));
        data.refunds = List.of(refund("FAILED", PRICE), new Refund(9, 1, 2, 5, 4, "SUCCEEDED", PRICE, false));
        assertTrue(check(data).isEmpty());
    }

    @Test
    void orderAggregateAndWholeHoldSeatSetMustMatch() {
        var data = fixture();
        data.orders = List.of(new Order(1, "PAID", new BigDecimal("101.00"), EXPIRY, false));
        assertRule(data, "C01_ORDER_STRUCTURE");
        data.orders = List.of(order("PAID"));
        data.holdItems = List.of();
        assertRule(data, "C01_ORDER_STRUCTURE");
    }

    @Test
    void pendingOrderMustOwnItsEntireOriginalSeatSet() {
        var data = fixture();
        data.orders = List.of(order("PENDING_PAYMENT"));
        data.holds = List.of(new Hold(3, 1, "ACTIVE", EXPIRY));
        data.payments = List.of(); data.tickets = List.of();
        data.seats = List.of(new Seat(6, "AVAILABLE", null));
        assertRule(data, "C02_PENDING_OWNERSHIP");
    }

    @Test
    void cancelledOrderCannotKeepAnUnresolvedPaymentOrOldOwnership() {
        var data = fixture();
        data.orders = List.of(order("CANCELLED"));
        data.holds = List.of(new Hold(3, 1, "RELEASED", EXPIRY));
        data.payments = List.of(new Payment(4, 1, "PROCESSING", PRICE, EXPIRY));
        data.tickets = List.of(); data.seats = List.of(new Seat(6, "HELD", 3L));
        assertRule(data, "C03_CANCELLED_ORDER");
    }

    @Test
    void paidOrderRequiresTicketsForEveryItemAndExactlyOneSuccess() {
        var data = fixture(); data.tickets = List.of();
        assertRule(data, "C04_PAID_FULFILLMENT");
        data = fixture(); data.payments = List.of();
        assertRule(data, "C04_PAID_FULFILLMENT");
    }

    @Test
    void everyPaymentPreservesOriginalAmountAndDeadline() {
        var data = fixture();
        data.payments = List.of(new Payment(4, 1, "SUCCEEDED", new BigDecimal("99.00"), EXPIRY.plusSeconds(1)));
        assertRule(data, "C04_PAYMENT_BINDING");
    }

    @Test
    void soldSeatRequiresExactlyOneCurrentOccupyingTicket() {
        var data = fixture(); data.tickets = List.of();
        assertRule(data, "C05_SEAT_TICKET");
        data = fixture(); data.seats = List.of(new Seat(6, "AVAILABLE", null));
        assertRule(data, "C05_SEAT_TICKET");
    }

    @Test
    void heldSeatRequiresActivePendingOrderAndItsDetail() {
        var data = fixture();
        data.seats = List.of(new Seat(6, "HELD", 3L));
        assertRule(data, "C06_HELD_BINDING");
    }

    @Test
    void refundingAndRefundedTicketsRequireTheirCorrespondingRefundFacts() {
        var data = fixture(); data.tickets = List.of(ticket("REFUNDING"));
        assertRule(data, "C07_TICKET_REFUND");
        data.tickets = List.of(ticket("REFUNDED"));
        assertRule(data, "C07_TICKET_REFUND");
    }

    @Test
    void refundUsesOriginalItemPriceAndSuccessfulPayment() {
        var data = fixture(); data.refunds = List.of(refund("FAILED", new BigDecimal("99.00")));
        assertRule(data, "C07_REFUND_BINDING");
    }

    @Test
    void pendingOrSuccessfulRefundCannotDisagreeWithTicketState() {
        var data = fixture(); data.refunds = List.of(refund("PROCESSING", PRICE));
        assertRule(data, "C07_REFUND_BINDING");
        data.refunds = List.of(refund("SUCCEEDED", PRICE));
        assertRule(data, "C07_REFUND_BINDING");
    }

    @Test
    void orderRefundSummaryComesFromSuccessfulRefundsRatherThanFailedHistory() {
        var data = fixture(); data.orders = List.of(order("FULLY_REFUNDED"));
        data.refunds = List.of(refund("FAILED", PRICE));
        assertRule(data, "C08_REFUND_SUMMARY");
    }

    @Test
    void checkedInTicketRequiresExactlyOneSuccessAudit() {
        var data = fixture(); data.tickets = List.of(ticket("CHECKED_IN"));
        assertRule(data, "C09_CHECKIN_AUDIT");
    }

    @Test
    void successAuditRequiresCheckedInTicketAndMatchingActualAndSubmittedSession() {
        var data = fixture(); data.checkins = List.of(new Checkin(8, 5L, 7L, 7));
        assertRule(data, "C09_CHECKIN_AUDIT");
        data.tickets = List.of(ticket("CHECKED_IN"));
        data.checkins = List.of(new Checkin(8, 5L, 8L, 7));
        assertRule(data, "C09_CHECKIN_AUDIT");
        data.checkins = List.of(new Checkin(8, 5L, 7L, 8));
        assertRule(data, "C09_CHECKIN_AUDIT");
    }

    @Test
    void validUnattendedOrderIsNotAnErrorEvenWhenCutoffCompletionIsDeferred() {
        assertTrue(check(fixture()).isEmpty());
    }

    @Test
    void prematureCompletionIsReportedButCompletionAtCutoffCanKeepUnattendedValidTickets() {
        var data = fixture(); data.orders = List.of(order("COMPLETED"));
        assertRule(data, "C08_REFUND_SUMMARY");
        assertTrue(rules.check(7, EXPIRY.plusSeconds(3600), EXPIRY.plusSeconds(3600), data.snapshot()).isEmpty());
    }

    @Test
    void fullyCheckedInOrderMustRetainTheAtomicCompletedSummary() {
        var data = fixture(); data.tickets = List.of(ticket("CHECKED_IN"));
        data.checkins = List.of(new Checkin(8, 5L, 7L, 7));
        assertRule(data, "C08_REFUND_SUMMARY");
        data.orders = List.of(order("COMPLETED"));
        assertTrue(check(data).isEmpty());
    }

    private List<ConsistencyModels.Difference> check(Data data) { return rules.check(7, EXPIRY.minusSeconds(900), EXPIRY.plusSeconds(3600), data.snapshot()); }
    private void assertRule(Data data, String rule) { assertTrue(check(data).stream().anyMatch(d -> d.rule().equals(rule)), () -> check(data).toString()); }
    private static Order order(String status) { return new Order(1, status, PRICE, EXPIRY, false); }
    private static Ticket ticket(String status) { return new Ticket(5, 1, 2, 6, status); }
    private static Refund refund(String status, BigDecimal amount) { return new Refund(8, 1, 2, 5, 4, status, amount, false); }
    private static Data fixture() { return new Data(); }

    private static class Data {
        List<Order> orders = List.of(order("PAID"));
        List<Item> items = List.of(new Item(2, 1, 6, PRICE));
        List<Hold> holds = List.of(new Hold(3, 1, "CONSUMED", EXPIRY));
        List<HoldItem> holdItems = List.of(new HoldItem(3, 6));
        List<Payment> payments = List.of(new Payment(4, 1, "SUCCEEDED", PRICE, EXPIRY));
        List<Seat> seats = List.of(new Seat(6, "SOLD", null));
        List<Ticket> tickets = List.of(ticket("VALID"));
        List<Refund> refunds = List.of();
        List<Checkin> checkins = List.of();
        ConsistencySnapshot snapshot() { return new ConsistencySnapshot(orders, items, holds, holdItems, payments, seats, tickets, refunds, checkins); }
    }
}
