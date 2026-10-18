package com.ticketing.consistency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import static com.ticketing.consistency.ConsistencyModels.*;
import static com.ticketing.consistency.ConsistencySnapshot.*;

/** Pure checks against one committed snapshot. History is never mistaken for current seat ownership. */
final class ConsistencyRules {
    private static final Set<String> PAID = Set.of("PAID", "PARTIALLY_REFUNDED", "FULLY_REFUNDED", "COMPLETED");
    private static final Set<String> OCCUPYING = Set.of("VALID", "REFUNDING", "CHECKED_IN");
    private static final Set<String> OPEN_PAYMENT = Set.of("INITIATED", "PROCESSING", "SUCCEEDED");
    private static final Set<String> OPEN_REFUND = Set.of("REQUESTED", "PROCESSING");
    private static final String ADVICE = "人工核对原订单、支付、锁组、票及退款记录；保留现场，通过既有恢复入口核查渠道事实，勿直接改库存";

    List<Difference> check(long sessionId, Instant checkedAt, Instant admissionCloseAt, ConsistencySnapshot data) {
        var index = new Index(data);
        var differences = new ArrayList<Difference>();
        for (var order : data.orders()) { checkOrder(order, checkedAt, admissionCloseAt, index, differences); }
        for (var seat : data.seats()) { checkSeat(seat, index, differences); }
        for (var ticket : data.tickets()) { checkTicket(ticket, index, differences); }
        for (var refund : data.refunds()) { checkRefund(refund, index, differences); }
        for (var checkin : data.checkins()) {
            var ticket = index.tickets.get(checkin.ticketId());
            if (ticket == null || !ticket.ticketStatus().equals("CHECKED_IN")
                    || checkin.sessionId() == null || checkin.sessionId() != sessionId
                    || checkin.submittedSessionId() != sessionId) {
                add(differences, "C09_CHECKIN_AUDIT", "CHECKIN", checkin.id(),
                        "SUCCESS ticketId=" + checkin.ticketId() + ", sessionId=" + checkin.sessionId()
                                + ", submittedSessionId=" + checkin.submittedSessionId()
                                + ", ticketStatus=" + (ticket == null ? "MISSING_OR_OTHER_SESSION" : ticket.ticketStatus()));
            }
        }
        return List.copyOf(differences);
    }

    private void checkOrder(Order order, Instant checkedAt, Instant admissionCloseAt, Index index, List<Difference> differences) {
        var items = index.itemsByOrder.getOrDefault(order.id(), List.of());
        var holds = index.holdsByOrder.getOrDefault(order.id(), List.of());
        var tickets = index.ticketsByOrder.getOrDefault(order.id(), List.of());
        var payments = index.paymentsByOrder.getOrDefault(order.id(), List.of());
        var itemSeats = items.stream().map(Item::sessionSeatId).collect(Collectors.toSet());
        var total = items.stream().map(Item::transactionPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        var hold = holds.size() == 1 ? holds.getFirst() : null;
        boolean structure = !items.isEmpty() && total.compareTo(order.totalAmount()) == 0 && hold != null;
        if (hold != null) {
            structure &= order.paymentExpiresAt().equals(hold.expiresAt())
                    && itemSeats.equals(index.holdSeatIds(hold.id()));
        }
        if (!structure) {
            add(differences, "C01_ORDER_STRUCTURE", "ORDER", order.id(),
                    "itemCount=" + items.size() + ", itemTotal=" + total + ", orderTotal=" + order.totalAmount()
                            + ", holdCount=" + holds.size() + ", holdExpiryAndSeatSetMatch="
                            + (hold != null && order.paymentExpiresAt().equals(hold.expiresAt())
                                && itemSeats.equals(index.holdSeatIds(hold.id()))));
        }
        for (var payment : payments) {
            if (payment.amount().compareTo(order.totalAmount()) != 0
                    || !payment.paymentExpiresAt().equals(order.paymentExpiresAt())) {
                add(differences, "C04_PAYMENT_BINDING", "PAYMENT", payment.id(),
                        "orderId=" + order.id() + ", amount=" + payment.amount() + ", orderTotal=" + order.totalAmount()
                                + ", expiryMatches=" + payment.paymentExpiresAt().equals(order.paymentExpiresAt()));
            }
        }
        long successes = payments.stream().filter(p -> p.paymentStatus().equals("SUCCEEDED")).count();
        var owned = hold == null ? Set.<Long>of() : index.ownedSeatIds(hold.id());
        if (order.orderStatus().equals("PENDING_PAYMENT")) {
            if (hold == null || !hold.holdStatus().equals("ACTIVE") || !owned.equals(itemSeats)
                    || successes != 0 || !tickets.isEmpty()) {
                add(differences, "C02_PENDING_OWNERSHIP", "ORDER", order.id(),
                        "holdStatus=" + (hold == null ? "MISSING" : hold.holdStatus()) + ", ownedSeatCount=" + owned.size()
                                + ", itemCount=" + items.size() + ", succeededPayments=" + successes + ", ticketCount=" + tickets.size());
            }
        } else if (order.orderStatus().equals("CANCELLED")) {
            if (hold == null || !Set.of("RELEASED", "EXPIRED").contains(hold.holdStatus()) || !owned.isEmpty()
                    || !tickets.isEmpty() || payments.stream().anyMatch(p -> OPEN_PAYMENT.contains(p.paymentStatus()))) {
                add(differences, "C03_CANCELLED_ORDER", "ORDER", order.id(),
                        "holdStatus=" + (hold == null ? "MISSING" : hold.holdStatus()) + ", oldHoldOwnedSeats=" + owned.size()
                                + ", ticketCount=" + tickets.size() + ", activeOrSuccessfulPayment="
                                + payments.stream().anyMatch(p -> OPEN_PAYMENT.contains(p.paymentStatus())));
            }
            // A released historical seat may now be HELD or SOLD by a new order.
        } else if (PAID.contains(order.orderStatus())) {
            var ticketItemIds = tickets.stream().map(Ticket::orderItemId).collect(Collectors.toSet());
            var itemIds = items.stream().map(Item::id).collect(Collectors.toSet());
            if (successes != 1 || hold == null || !hold.holdStatus().equals("CONSUMED") || !owned.isEmpty()
                    || tickets.size() != items.size() || !ticketItemIds.equals(itemIds)) {
                add(differences, "C04_PAID_FULFILLMENT", "ORDER", order.id(),
                        "status=" + order.orderStatus() + ", succeededPayments=" + successes + ", holdStatus="
                                + (hold == null ? "MISSING" : hold.holdStatus()) + ", oldHoldOwnedSeats=" + owned.size()
                                + ", itemCount=" + items.size() + ", ticketCount=" + tickets.size());
            }
            long refunded = index.refundsByOrder.getOrDefault(order.id(), List.of()).stream()
                    .filter(r -> r.refundStatus().equals("SUCCEEDED")).map(Refund::ticketId).distinct().count();
            boolean pendingRefund = index.refundsByOrder.getOrDefault(order.id(), List.of()).stream()
                    .anyMatch(r -> OPEN_REFUND.contains(r.refundStatus()));
            boolean fullyCheckedIn = tickets.stream().anyMatch(t -> t.ticketStatus().equals("CHECKED_IN"))
                    && tickets.stream().allMatch(t -> Set.of("REFUNDED", "CHECKED_IN").contains(t.ticketStatus()))
                    && !pendingRefund;
            boolean summary = switch (order.orderStatus()) {
                case "PAID" -> refunded == 0 && !fullyCheckedIn;
                case "PARTIALLY_REFUNDED" -> refunded > 0 && refunded < items.size() && !fullyCheckedIn;
                case "FULLY_REFUNDED" -> !items.isEmpty() && refunded == items.size();
                // Cutoff-based completion is deferred. Do not report an eligible but unfinished order as a fault.
                case "COMPLETED" -> refunded < items.size()
                        && tickets.stream().noneMatch(t -> t.ticketStatus().equals("REFUNDING"))
                        && !pendingRefund
                        && (!checkedAt.isBefore(admissionCloseAt)
                            || tickets.stream().allMatch(t -> Set.of("REFUNDED", "CHECKED_IN").contains(t.ticketStatus())));
                default -> true;
            };
            if (!summary) {
                add(differences, "C08_REFUND_SUMMARY", "ORDER", order.id(),
                        "status=" + order.orderStatus() + ", itemCount=" + items.size() + ", succeededRefundTickets=" + refunded);
            }
        }
    }

    private void checkSeat(Seat seat, Index index, List<Difference> differences) {
        var occupying = index.ticketsBySeat.getOrDefault(seat.id(), List.of()).stream()
                .filter(t -> OCCUPYING.contains(t.ticketStatus())).toList();
        if (seat.seatStatus().equals("SOLD") && (occupying.size() != 1 || seat.currentHoldId() != null)
                || !seat.seatStatus().equals("SOLD") && !occupying.isEmpty()) {
            add(differences, "C05_SEAT_TICKET", "SEAT", seat.id(),
                    "status=" + seat.seatStatus() + ", currentHoldId=" + seat.currentHoldId() + ", occupyingTickets=" + occupying.size());
        }
        if (seat.seatStatus().equals("HELD")) {
            var hold = index.holds.get(seat.currentHoldId());
            var order = hold == null ? null : index.orders.get(hold.orderId());
            if (hold == null || !hold.holdStatus().equals("ACTIVE") || order == null
                    || !order.orderStatus().equals("PENDING_PAYMENT") || !index.holdSeatIds(hold.id()).contains(seat.id())
                    || index.itemsByOrder.getOrDefault(order.id(), List.of()).stream().noneMatch(i -> i.sessionSeatId() == seat.id())) {
                add(differences, "C06_HELD_BINDING", "SEAT", seat.id(),
                        "currentHoldId=" + seat.currentHoldId() + ", holdStatus=" + (hold == null ? "MISSING" : hold.holdStatus())
                                + ", orderStatus=" + (order == null ? "MISSING" : order.orderStatus()));
            }
        }
    }

    private void checkTicket(Ticket ticket, Index index, List<Difference> differences) {
        var seat = index.seats.get(ticket.sessionSeatId());
        if (OCCUPYING.contains(ticket.ticketStatus()) && (seat == null || !seat.seatStatus().equals("SOLD") || seat.currentHoldId() != null)) {
            add(differences, "C05_SEAT_TICKET", "TICKET", ticket.id(),
                    "status=" + ticket.ticketStatus() + ", seatId=" + ticket.sessionSeatId() + ", seatStatus=" + (seat == null ? "MISSING" : seat.seatStatus()));
        }
        var refunds = index.refundsByTicket.getOrDefault(ticket.id(), List.of());
        long pending = refunds.stream().filter(r -> OPEN_REFUND.contains(r.refundStatus())).count();
        long succeeded = refunds.stream().filter(r -> r.refundStatus().equals("SUCCEEDED")).count();
        if (ticket.ticketStatus().equals("REFUNDING") && (pending != 1 || succeeded != 0)
                || ticket.ticketStatus().equals("REFUNDED") && (succeeded != 1 || pending != 0)) {
            add(differences, "C07_TICKET_REFUND", "TICKET", ticket.id(),
                    "status=" + ticket.ticketStatus() + ", pendingRefunds=" + pending + ", succeededRefunds=" + succeeded);
        }
        long successAudits = index.checkinsByTicket.getOrDefault(ticket.id(), List.of()).size();
        if (ticket.ticketStatus().equals("CHECKED_IN") && successAudits != 1) {
            add(differences, "C09_CHECKIN_AUDIT", "TICKET", ticket.id(), "status=CHECKED_IN, successAudits=" + successAudits);
        }
    }

    private void checkRefund(Refund refund, Index index, List<Difference> differences) {
        var item = index.items.get(refund.orderItemId());
        var ticket = index.tickets.get(refund.ticketId());
        var payment = index.payments.get(refund.paymentId());
        boolean binding = item != null && ticket != null && payment != null
                && item.orderId() == refund.orderId() && ticket.orderId() == refund.orderId()
                && ticket.orderItemId() == refund.orderItemId() && payment.orderId() == refund.orderId()
                && payment.paymentStatus().equals("SUCCEEDED") && refund.amount().compareTo(item.transactionPrice()) == 0;
        boolean status = ticket != null && (!OPEN_REFUND.contains(refund.refundStatus()) || ticket.ticketStatus().equals("REFUNDING"))
                && (!refund.refundStatus().equals("SUCCEEDED") || ticket.ticketStatus().equals("REFUNDED"));
        // FAILED history imposes no current ticket status: a later retry or check-in may already have completed.
        if (!binding || !status) {
            add(differences, "C07_REFUND_BINDING", "REFUND", refund.id(),
                    "status=" + refund.refundStatus() + ", ticketId=" + refund.ticketId() + ", ticketStatus="
                            + (ticket == null ? "MISSING" : ticket.ticketStatus()) + ", amount=" + refund.amount()
                            + ", itemPrice=" + (item == null ? "MISSING" : item.transactionPrice()) + ", originalPaidBinding=" + binding);
        }
    }

    private void add(List<Difference> differences, String rule, String entity, long id, String observed) {
        differences.add(new Difference(rule, entity, id, Severity.ERROR, observed, ADVICE));
    }

    private static final class Index {
        final Map<Long, Order> orders;
        final Map<Long, Item> items;
        final Map<Long, Hold> holds;
        final Map<Long, Payment> payments;
        final Map<Long, Seat> seats;
        final Map<Long, Ticket> tickets;
        final Map<Long, List<Item>> itemsByOrder;
        final Map<Long, List<Hold>> holdsByOrder;
        final Map<Long, List<Payment>> paymentsByOrder;
        final Map<Long, List<Ticket>> ticketsByOrder;
        final Map<Long, List<Ticket>> ticketsBySeat;
        final Map<Long, List<Refund>> refundsByOrder;
        final Map<Long, List<Refund>> refundsByTicket;
        final Map<Long, List<Checkin>> checkinsByTicket;
        final Map<Long, Set<Long>> holdSeats;
        final Map<Long, Set<Long>> ownedSeats;

        Index(ConsistencySnapshot data) {
            orders = byId(data.orders(), Order::id); items = byId(data.items(), Item::id);
            holds = byId(data.holds(), Hold::id); payments = byId(data.payments(), Payment::id);
            seats = byId(data.seats(), Seat::id); tickets = byId(data.tickets(), Ticket::id);
            itemsByOrder = data.items().stream().collect(Collectors.groupingBy(Item::orderId));
            holdsByOrder = data.holds().stream().collect(Collectors.groupingBy(Hold::orderId));
            paymentsByOrder = data.payments().stream().collect(Collectors.groupingBy(Payment::orderId));
            ticketsByOrder = data.tickets().stream().collect(Collectors.groupingBy(Ticket::orderId));
            ticketsBySeat = data.tickets().stream().collect(Collectors.groupingBy(Ticket::sessionSeatId));
            refundsByOrder = data.refunds().stream().collect(Collectors.groupingBy(Refund::orderId));
            refundsByTicket = data.refunds().stream().collect(Collectors.groupingBy(Refund::ticketId));
            checkinsByTicket = data.checkins().stream().filter(c -> c.ticketId() != null).collect(Collectors.groupingBy(Checkin::ticketId));
            holdSeats = data.holdItems().stream().collect(Collectors.groupingBy(HoldItem::holdId,
                    Collectors.mapping(HoldItem::sessionSeatId, Collectors.toSet())));
            ownedSeats = data.seats().stream().filter(s -> s.currentHoldId() != null).collect(Collectors.groupingBy(Seat::currentHoldId,
                    Collectors.mapping(Seat::id, Collectors.toSet())));
        }

        Set<Long> holdSeatIds(long id) { return holdSeats.getOrDefault(id, Set.of()); }
        Set<Long> ownedSeatIds(long id) { return ownedSeats.getOrDefault(id, Set.of()); }
        private static <T> Map<Long, T> byId(List<T> rows, Function<T, Long> id) {
            return rows.stream().collect(Collectors.toMap(id, Function.identity()));
        }
    }
}
