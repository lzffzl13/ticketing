package com.ticketing.ticketing;

import com.ticketing.common.persistence.GeneratedKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static com.ticketing.ticketing.TicketModels.*;

/** Owns ticket transitions; trade checks ownership and coordinates the full result transaction. */
@Service
public class TicketService {
    private final TicketMapper mapper;

    public TicketService(TicketMapper mapper) { this.mapper = mapper; }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<Ticket> issue(long orderId, long sessionId, List<IssueItem> items, Instant at) {
        if (orderId <= 0 || sessionId <= 0 || at == null || items == null || items.isEmpty()
                || items.stream().anyMatch(item -> item == null || item.orderItemId() == null
                        || item.orderItemId() <= 0 || item.sessionSeatId() == null || item.sessionSeatId() <= 0)) {
            throw new IllegalArgumentException("Ticket issuance requires complete order items and valid context");
        }
        if (new HashSet<>(items.stream().map(IssueItem::orderItemId).toList()).size() != items.size()
                || new HashSet<>(items.stream().map(IssueItem::sessionSeatId).toList()).size() != items.size()) {
            throw new IllegalArgumentException("Each issued ticket must refer to a distinct order item and seat");
        }
        if (!mapper.byOrder(orderId).isEmpty()) {
            throw new IllegalStateException("The result transaction must handle already-issued orders before issuance");
        }
        for (var item : items.stream().sorted(Comparator.comparing(IssueItem::sessionSeatId)).toList()) {
            var ticket = new Ticket(null, UUID.randomUUID().toString(), item.orderItemId(), orderId,
                    sessionId, item.sessionSeatId(), TicketStatus.VALID, at);
            var key = new GeneratedKey();
            if (mapper.insertTicket(ticket, key) != 1 || key.getId() == null) {
                throw new IllegalStateException("Ticket was not completely saved");
            }
        }
        var issued = mapper.byOrder(orderId);
        if (issued.size() != items.size()) {
            throw new IllegalStateException("The whole order was not issued");
        }
        return List.copyOf(issued);
    }

    /** Joins the caller's read snapshot; the caller authorizes this order before exposing its tickets. */
    @Transactional(readOnly = true)
    public List<Ticket> listByOrder(long orderId) {
        return List.copyOf(mapper.byOrder(orderId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Ticket lock(long ticketId) { return mapper.lock(ticketId); }

    /** Called only after the credential has been authenticated; this lookup grants no admission. */
    public Ticket findByNumber(String number) { return mapper.byNumber(number); }

    @Transactional(propagation = Propagation.MANDATORY)
    public void checkIn(long ticketId, Instant at) {
        if (mapper.transition(ticketId, TicketStatus.VALID, TicketStatus.CHECKED_IN, at) != 1) {
            throw new IllegalStateException("Ticket check-in did not match its current state");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void transition(long ticketId, TicketStatus expected, TicketStatus target, Instant at) {
        boolean allowed = expected==TicketStatus.VALID && target==TicketStatus.REFUNDING
                || expected==TicketStatus.REFUNDING && (target==TicketStatus.VALID || target==TicketStatus.REFUNDED);
        if (!allowed || mapper.transition(ticketId, expected, target, at)!=1) {
            throw new IllegalStateException("Ticket refund transition did not match its current state");
        }
    }
}
