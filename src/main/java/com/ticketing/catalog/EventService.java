package com.ticketing.catalog;

import com.ticketing.common.persistence.GeneratedKey;
import com.ticketing.common.web.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import com.ticketing.catalog.CatalogModels.Event;
import com.ticketing.catalog.CatalogRequests.CreateEvent;

@Service
public class EventService {
    private final CatalogMapper mapper;
    private final Clock clock;

    public EventService(CatalogMapper mapper, Clock clock) { this.mapper = mapper; this.clock = clock; }

    @Transactional
    public Event create(CreateEvent request) {
        var key = new GeneratedKey();
        mapper.insertEvent(request, clock.instant(), key);
        return get(key.getId());
    }

    public Event get(long id) { return required(mapper.eventById(id)); }

    public Event getByCode(String code) { return required(mapper.eventByCode(code)); }

    private Event required(Event event) {
        if (event == null) { throw new BusinessException("NOT_FOUND", "演出不存在", HttpStatus.NOT_FOUND); }
        return event;
    }
}
