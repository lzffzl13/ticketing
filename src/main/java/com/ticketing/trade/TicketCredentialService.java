package com.ticketing.trade;

import com.ticketing.ticketing.QrCodeRenderer;
import com.ticketing.ticketing.TicketCredentialCodec;
import org.springframework.stereotype.Service;

/** Encoding happens after the read transaction; failures never reopen a paid order or issue another ticket. */
@Service
public class TicketCredentialService {
    private final TicketCredentialQueryService queries;
    private final TicketCredentialCodec codec;
    private final QrCodeRenderer images;

    public TicketCredentialService(TicketCredentialQueryService queries, TicketCredentialCodec codec,
                                   QrCodeRenderer images) {
        this.queries = queries;
        this.codec = codec;
        this.images = images;
    }

    public TicketCredentialView credential(long customerId, long orderId, long ticketId) {
        var ticket = queries.eligible(customerId, orderId, ticketId);
        return new TicketCredentialView(ticket.id(), ticket.ticketNumber(), ticket.ticketStatus(),
                codec.encode(ticket.ticketNumber()));
    }

    public byte[] png(long customerId, long orderId, long ticketId) {
        return images.render(credential(customerId, orderId, ticketId).credential());
    }
}
