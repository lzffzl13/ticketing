package com.ticketing.venue;

import com.ticketing.common.persistence.GeneratedKey;
import com.ticketing.common.web.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static com.ticketing.venue.VenueModels.*;
import static com.ticketing.venue.VenueRequests.*;

@Service
public class VenueService {
    private final VenueMapper mapper;
    private final Clock clock;

    public VenueService(VenueMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public Venue createVenue(CreateVenue request) {
        var key = new GeneratedKey();
        mapper.insertVenue(request, clock.instant(), key);
        return getVenue(key.getId());
    }

    public Venue getVenue(long id) {
        return required(mapper.venueById(id), "场馆不存在");
    }

    public Venue getVenueByCode(String code) {
        return required(mapper.venueByCode(code), "场馆不存在");
    }

    @Transactional
    public Venue updateVenue(long id, UpdateVenue request) {
        getVenue(id);
        mapper.updateVenue(id, request, clock.instant());
        return getVenue(id);
    }

    @Transactional
    public Area createArea(long venueId, CreateArea request) {
        getVenue(venueId);
        var key = new GeneratedKey();
        mapper.insertArea(venueId, request, clock.instant(), key);
        return required(mapper.areaById(key.getId()), "区域不存在");
    }

    public List<Area> listAreas(long venueId) {
        getVenue(venueId);
        return mapper.areas(venueId);
    }

    @Transactional
    public Area updateArea(long id, UpdateArea request) {
        required(mapper.lockArea(id), "区域不存在");
        mapper.updateArea(id, request, clock.instant());
        return mapper.areaById(id);
    }

    @Transactional
    public List<SeatTemplate> addSeats(long areaId, List<SeatInput> requests) {
        // Only template writers serialize here; snapshot creation uses nonlocking reads.
        required(mapper.lockArea(areaId), "区域不存在");
        var result = new ArrayList<SeatTemplate>();
        var at = clock.instant();
        for (var request : requests) {
            validateRow(areaId, request.rowLabel(), request.rowOrder(), 0);
            var key = new GeneratedKey();
            mapper.insertSeat(areaId, request, at, key);
            result.add(mapper.seatById(key.getId()));
        }
        return List.copyOf(result);
    }

    public SeatTemplate getSeat(long id) {
        return required(mapper.seatById(id), "座位模板不存在");
    }

    @Transactional
    public SeatTemplate updateSeat(long id, UpdateSeat request) {
        var before = getSeat(id);
        // Lock areas in ID order, including both areas when moving a template seat.
        long first = Math.min(before.areaId(), request.areaId());
        long second = Math.max(before.areaId(), request.areaId());
        var firstArea = required(mapper.lockArea(first), "区域不存在");
        var secondArea = first == second ? firstArea : required(mapper.lockArea(second), "区域不存在");
        if (!firstArea.venueId().equals(secondArea.venueId())) {
            throw invalid("模板座位只能在同一场馆内移动");
        }
        var current = required(mapper.lockSeat(id), "座位模板不存在");
        if (!current.areaId().equals(before.areaId())) {
            throw new BusinessException("TEMPLATE_CHANGED", "模板已被其他操作移动，请重新读取后重试", HttpStatus.CONFLICT);
        }
        validateRow(request.areaId(), request.rowLabel(), request.rowOrder(), id);
        mapper.updateSeat(id, request, clock.instant());
        return mapper.seatById(id);
    }

    @Transactional
    public void deleteSeat(long id) {
        var before = getSeat(id);
        required(mapper.lockArea(before.areaId()), "区域不存在");
        var current = required(mapper.lockSeat(id), "座位模板不存在");
        if (!current.areaId().equals(before.areaId())) {
            throw new BusinessException("TEMPLATE_CHANGED", "模板已被其他操作移动，请重新读取后重试", HttpStatus.CONFLICT);
        }
        mapper.deleteSeat(id);
    }

    @Transactional(readOnly = true)
    public List<LayoutSeat> listLayoutSeats(long venueId) {
        getVenue(venueId);
        return mapper.layoutSeats(venueId);
    }

    /** Joins catalog's REPEATABLE READ transaction; these three queries are all ordinary SELECTs. */
    @Transactional(propagation = Propagation.MANDATORY)
    public VenueLayout readLayout(long venueId) {
        return new VenueLayout(getVenue(venueId), mapper.areas(venueId), mapper.layoutSeats(venueId));
    }

    private void validateRow(long areaId, String label, int order, long excludedId) {
        if (!mapper.conflictingRows(areaId, label, order, excludedId).isEmpty()) {
            throw invalid("同一排必须使用相同排序值，不同排不能使用同一排序值");
        }
    }

    private <T> T required(T value, String message) {
        if (value == null) {
            throw new BusinessException("NOT_FOUND", message, HttpStatus.NOT_FOUND);
        }
        return value;
    }

    private BusinessException invalid(String message) {
        return new BusinessException("INVALID_TEMPLATE", message, HttpStatus.BAD_REQUEST);
    }
}
