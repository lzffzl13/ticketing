package com.ticketing.trade;

import com.ticketing.catalog.SessionService;
import com.ticketing.common.web.BusinessException;
import com.ticketing.ticketing.TicketModels.Ticket;
import com.ticketing.ticketing.TicketModels.TicketStatus;
import com.ticketing.ticketing.TicketService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** Authorizes an existing ticket in one snapshot; this read never reserves admission. */
@Service
public class TicketCredentialQueryService {
    private final PaymentMapper orders;
    private final TicketService tickets;
    private final SessionService sessions;
    private final Clock clock;

    public TicketCredentialQueryService(PaymentMapper orders, TicketService tickets,
                                        SessionService sessions, Clock clock) {
        this.orders = orders;
        this.tickets = tickets;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public Ticket eligible(long customerId, long orderId, long ticketId) {
        var order = orders.orderState(orderId);
        if (order == null || order.customerId() != customerId) { throw missing(); }
        // Search only the authorized order; a real ticket in a different order is still 404.
        var ticket = tickets.listByOrder(orderId).stream().filter(item -> item.id() == ticketId)
                .findFirst().orElseThrow(TicketCredentialQueryService::missing);
        if (ticket.ticketStatus() != TicketStatus.VALID) {
            throw new BusinessException("TICKET_NOT_VALID", "当前票状态不允许获取入场码", HttpStatus.CONFLICT);
        }
        var session = sessions.get(ticket.sessionId());
        if (!clock.instant().isBefore(session.admissionCloseAt())) {
            throw new BusinessException("TICKET_ADMISSION_CLOSED", "已到入场截止时间，不能获取入场码", HttpStatus.CONFLICT);
        }
        return ticket;
    }

    private static BusinessException missing() {
        return new BusinessException("NOT_FOUND", "订单或电子票不存在", HttpStatus.NOT_FOUND);
    }
}
