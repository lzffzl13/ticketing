package com.ticketing.checkin;

import com.ticketing.catalog.CatalogPurchaseService;
import com.ticketing.common.persistence.GeneratedKey;
import com.ticketing.common.web.BusinessException;
import com.ticketing.inventory.InventoryHoldService;
import com.ticketing.ticketing.TicketCredentialCodec;
import com.ticketing.ticketing.TicketModels.Ticket;
import com.ticketing.ticketing.TicketModels.TicketStatus;
import com.ticketing.ticketing.TicketService;
import com.ticketing.trade.CheckinOrderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

import static com.ticketing.checkin.CheckinModels.*;

@Service
public class CheckinService {
    private final CheckinMapper mapper;
    private final CatalogPurchaseService catalog;
    private final CheckinOrderService orders;
    private final InventoryHoldService inventory;
    private final TicketService tickets;
    private final TicketCredentialCodec credentials;
    private final Clock clock;

    public CheckinService(CheckinMapper mapper, CatalogPurchaseService catalog, CheckinOrderService orders,
                          InventoryHoldService inventory, TicketService tickets,
                          TicketCredentialCodec credentials, Clock clock) {
        this.mapper = mapper; this.catalog = catalog; this.orders = orders; this.inventory = inventory;
        this.tickets = tickets; this.credentials = credentials; this.clock = clock;
    }

    /** One short transaction owns admission and its audit; no external calls occur while locks are held. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Attempt scan(long checkerId, long sessionId, String credential, String requestId) {
        String safeRequestId = requestId == null ? UUID.randomUUID().toString() : requestId;
        String digest = digest(credential);
        var session = catalog.lockForCheckin(sessionId);
        if (session == null) {
            return save(checkerId, sessionId, null, null, digest, safeRequestId, Result.SESSION_NOT_FOUND, now());
        }
        String number;
        try { number = credentials.verify(credential); }
        catch (BusinessException invalid) {
            if (!"INVALID_CREDENTIAL".equals(invalid.getCode())) { throw invalid; }
            return save(checkerId, sessionId, sessionId, null, digest, safeRequestId, Result.INVALID_CREDENTIAL, now());
        }
        // Never look up an unauthenticated payload. Immutable identity lookup does not authorize entry.
        var located = tickets.findByNumber(number);
        if (located == null) {
            return save(checkerId, sessionId, sessionId, null, digest, safeRequestId, Result.INVALID_CREDENTIAL, now());
        }
        if (located.sessionId() != sessionId) {
            return save(checkerId, sessionId, sessionId, located.id(), digest, safeRequestId, Result.WRONG_SESSION, now());
        }
        var order = orders.lock(located.orderId());
        // Terminal/refunding tickets are rejected before touching their potentially resold old seat.
        if (located.ticketStatus() != TicketStatus.VALID) {
            var current = tickets.lock(located.id());
            return save(checkerId, sessionId, sessionId, current.id(), digest, safeRequestId,
                    ticketResult(current.ticketStatus()), now());
        }
        boolean sold = inventory.lockSoldSeatForCheckin(sessionId, located.sessionSeatId());
        var current = tickets.lock(located.id());
        var at = now(); // Includes all lock waits, including admission-close crossings.
        Result result;
        if (current.ticketStatus() != TicketStatus.VALID) { result = ticketResult(current.ticketStatus()); }
        else if (at.isBefore(session.admissionOpenAt())) { result = Result.TOO_EARLY; }
        else if (!at.isBefore(session.admissionCloseAt())) { result = Result.TOO_LATE; }
        else if (!sold || !orders.permitsCheckin(order, sessionId)) { result = Result.INCONSISTENT_STATE; }
        else {
            tickets.checkIn(current.id(), at);
            orders.completeIfFulfilled(current.orderId(), at);
            result = Result.SUCCESS;
        }
        return save(checkerId, sessionId, sessionId, current.id(), digest, safeRequestId, result, at);
    }

    private Result ticketResult(TicketStatus status) {
        return switch (status) {
            case CHECKED_IN -> Result.ALREADY_CHECKED_IN;
            case REFUNDING -> Result.REFUNDING;
            case REFUNDED -> Result.REFUNDED;
            case VALID -> throw new IllegalStateException("Expected a non-valid ticket");
        };
    }

    private Attempt save(long checkerId, long submitted, Long sessionId, Long ticketId, String digest,
                         String requestId, Result result, Instant at) {
        var attempt = new Attempt(null, requestId, checkerId, submitted, sessionId, ticketId, digest, at, result);
        var key = new GeneratedKey();
        if (mapper.insert(attempt, key) != 1 || key.getId() == null) {
            throw new IllegalStateException("Check-in audit was not saved");
        }
        return new Attempt(key.getId(), requestId, checkerId, submitted, sessionId, ticketId, digest, at, result);
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }

    private static String digest(String credential) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(credential.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("Credential digest is unavailable");
        }
    }
}
