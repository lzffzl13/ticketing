package com.ticketing.trade;

import com.ticketing.ticketing.TicketModels.TicketStatus;

/** Sensitive response: no logging, URL embedding or shared caching of credential. */
public record TicketCredentialView(Long ticketId, String ticketNumber, TicketStatus ticketStatus, String credential) { }
