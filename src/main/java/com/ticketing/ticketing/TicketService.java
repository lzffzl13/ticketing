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

/** Owns issuance only; trade checks order ownership and coordinates the full result transaction. */
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
}
