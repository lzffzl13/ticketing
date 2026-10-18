package com.ticketing.consistency;

import com.ticketing.common.web.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import static com.ticketing.consistency.ConsistencyModels.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
public class ConsistencyService {
    private final ConsistencyMapper mapper;
    private final Clock clock;
    private final ConsistencyRules rules = new ConsistencyRules();

    public ConsistencyService(ConsistencyMapper mapper, Clock clock) { this.mapper = mapper; this.clock = clock; }

    /** No channel calls, business writes, FOR UPDATE reads, or nested REQUIRES_NEW query services. */
    public SessionReport inspect(long sessionId) {
        // This first SELECT establishes the snapshot used by every subsequent table read.
        var session = mapper.session(sessionId);
        if (session == null) {
            throw new BusinessException("NOT_FOUND", "场次不存在", HttpStatus.NOT_FOUND);
        }
        var checkedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var snapshot = new ConsistencySnapshot(mapper.orders(sessionId), mapper.items(sessionId),
                mapper.holds(sessionId), mapper.holdItems(sessionId), mapper.payments(sessionId),
                mapper.seats(sessionId), mapper.tickets(sessionId), mapper.refunds(sessionId), mapper.checkins(sessionId));
        var differences = rules.check(sessionId, checkedAt, session.admissionCloseAt(), snapshot);
        return new SessionReport(sessionId, checkedAt, differences.isEmpty(),
                new Counts(snapshot.orders().size(), snapshot.seats().size(), snapshot.tickets().size(),
                        snapshot.refunds().size(), snapshot.checkins().size()),
                new RecoverySummary(snapshot.orders().stream().filter(o -> o.orderStatus().equals("PENDING_PAYMENT")).count(),
                        snapshot.payments().stream().filter(p -> List.of("INITIATED", "PROCESSING").contains(p.paymentStatus())).count(),
                        snapshot.orders().stream().filter(ConsistencySnapshot.Order::manualRequired).count(),
                        snapshot.refunds().stream().filter(r -> List.of("REQUESTED", "PROCESSING").contains(r.refundStatus())).count(),
                        snapshot.refunds().stream().filter(ConsistencySnapshot.Refund::manualRequired).count()), differences);
    }

    public long upperBound() { return mapper.upperBound(); }
    public List<Long> sessions(long after, long upper, int limit) { return mapper.sessions(after, upper, limit); }
}
