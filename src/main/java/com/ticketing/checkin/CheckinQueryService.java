package com.ticketing.checkin;

import com.ticketing.common.web.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import static com.ticketing.checkin.CheckinModels.*;

@Service
public class CheckinQueryService {
    private final CheckinMapper mapper;
    public CheckinQueryService(CheckinMapper mapper) { this.mapper = mapper; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page query(Long sessionId, Long ticketId, Instant from, Instant to, long afterId, int limit) {
        if (limit < 1 || limit > 100 || afterId < 0 || sessionId != null && sessionId <= 0
                || ticketId != null && ticketId <= 0 || from != null && to != null && !from.isBefore(to)) {
            throw new BusinessException("INVALID_REQUEST", "查询范围或分页参数无效", HttpStatus.BAD_REQUEST);
        }
        var found = mapper.query(sessionId, ticketId, from, to, afterId, limit + 1);
        boolean more = found.size() > limit;
        var page = more ? found.subList(0, limit) : found;
        return new Page(page, more ? page.getLast().id() : null);
    }
}
