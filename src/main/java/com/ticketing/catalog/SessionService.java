package com.ticketing.catalog;

import com.ticketing.common.persistence.GeneratedKey;
import com.ticketing.common.web.BusinessException;
import com.ticketing.inventory.InventoryService;
import com.ticketing.inventory.InventoryModels.SessionSeat;
import com.ticketing.inventory.InventoryModels.SeatStatus;
import com.ticketing.venue.VenueService;
import com.ticketing.venue.VenueModels.VenueLayout;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;

import static com.ticketing.catalog.CatalogModels.*;
import static com.ticketing.catalog.CatalogRequests.*;

@Service
public class SessionService {
    private final CatalogMapper mapper;
    private final EventService events;
    private final VenueService venues;
    private final InventoryService inventory;
    private final Clock clock;

    public SessionService(CatalogMapper mapper, EventService events, VenueService venues,
                          InventoryService inventory, Clock clock) {
        this.mapper = mapper;
        this.events = events;
        this.venues = venues;
        this.inventory = inventory;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public CreatedSession create(CreateSession request) {
        // 已存在的代码直接冲突；下面的唯一键仍负责兜住“同时查到不存在”的并发请求。
        if (mapper.sessionByCode(request.code()) != null) { throw duplicateCode(); }
        // 1. 普通查询读取同一数据库版本，本流程不修改来源模板。
        var event = events.get(request.eventId());
        var layout = venues.readLayout(request.venueId());
        if (!event.enabled() || !layout.venue().enabled()) { throw invalid("演出或场馆已停用"); }
        if (layout.seats().isEmpty()) { throw invalid("场馆没有座位模板"); }
        validateTimes(request);
        var assignedTierCodes = assignPrices(request, layout);
        var at = clock.instant();

        // 2. 唯一键是防重复的最终边界；场次、票档和座位共用这一个事务。
        var session = new Session(null, request.code(), event.id(), layout.venue().id(), event.name(),
                layout.venue().name(), request.startsAt(), request.admissionOpenAt(), request.admissionCloseAt(),
                SalesStatus.DRAFT, null, request.forbidNewOrphan(), at, at);
        var key = new GeneratedKey();
        try {
            mapper.insertSession(session, key);
        } catch (DuplicateKeyException exception) {
            throw duplicateCode();
        }
        long sessionId = key.getId();
        for (var tier : request.priceTiers()) {
            mapper.insertTier(sessionId, tier, at, new GeneratedKey());
        }
        var tiersByCode = mapper.tiers(sessionId).stream().collect(Collectors.toMap(PriceTier::code, tier -> tier));

        // 3. 显式复制展示和价格字段；不可售座位也保留布局。
        var snapshots = new ArrayList<SessionSeat>();
        for (var source : layout.seats()) {
            var tier = tiersByCode.get(assignedTierCodes.get(source.id()));
            snapshots.add(new SessionSeat(null, sessionId, source.id(), source.areaId(), source.areaCode(),
                    source.areaName(), source.areaDisplayOrder(), source.rowLabel(), source.rowOrder(), source.seatNumber(),
                    source.coordinateX(), source.coordinateY(), source.aisleSegment(),
                    tier == null ? null : tier.id(), tier == null ? null : tier.name(), tier == null ? null : tier.price(),
                    source.enabled() ? SeatStatus.AVAILABLE : SeatStatus.UNAVAILABLE, at, at));
        }
        inventory.createSnapshots(sessionId, snapshots);
        int available = (int) snapshots.stream().filter(seat -> seat.seatStatus() == SeatStatus.AVAILABLE).count();
        // 方法正常返回后由 Spring 提交；任何写入异常向外抛出时，全部回滚。
        return new CreatedSession(sessionId, request.code(), snapshots.size(), available, snapshots.size() - available);
    }

    public Session get(long id) { return required(mapper.sessionById(id)); }

    public Session getByCode(String code) { return required(mapper.sessionByCode(code)); }

    public List<PriceTier> listTiers(long sessionId) {
        get(sessionId);
        return mapper.tiers(sessionId);
    }

    public List<SessionSeat> listSeats(long sessionId) {
        get(sessionId);
        return inventory.listSeats(sessionId);
    }

    private void validateTimes(CreateSession request) {
        if (!request.admissionOpenAt().isBefore(request.admissionCloseAt())
                || request.admissionCloseAt().isAfter(request.startsAt())) {
            throw invalid("入场时间必须满足开放时间 < 截止时间 <= 开场时间");
        }
    }

    private Map<Long, String> assignPrices(CreateSession request, VenueLayout layout) {
        var tierCodes = new HashSet<String>();
        for (var tier : request.priceTiers()) {
            if (!tierCodes.add(tier.code())) { throw invalid("票档代码重复"); }
            if (tier.price().signum() <= 0) { throw invalid("票价必须大于零"); }
        }
        var knownAreas = layout.areas().stream().map(area -> area.id()).collect(Collectors.toSet());
        var areaPrices = new HashMap<Long, String>();
        for (var mapping : request.areaPrices()) {
            if (!knownAreas.contains(mapping.areaId()) || !tierCodes.contains(mapping.priceTierCode())) {
                throw invalid("区域或票档不属于本次配置");
            }
            if (areaPrices.putIfAbsent(mapping.areaId(), mapping.priceTierCode()) != null) {
                throw invalid("区域票档映射重复");
            }
        }
        var knownSeats = layout.seats().stream().map(seat -> seat.id()).collect(Collectors.toSet());
        var overrides = new HashMap<Long, String>();
        for (var override : request.seatOverrides()) {
            if (!knownSeats.contains(override.seatTemplateId()) || !tierCodes.contains(override.priceTierCode())) {
                throw invalid("覆盖座位或票档不属于本次配置");
            }
            if (overrides.putIfAbsent(override.seatTemplateId(), override.priceTierCode()) != null) {
                throw invalid("单座票档覆盖重复");
            }
        }
        var result = new HashMap<Long, String>();
        for (var seat : layout.seats()) {
            String code = overrides.getOrDefault(seat.id(), areaPrices.get(seat.areaId()));
            if (seat.enabled() && code == null) { throw invalid("可售座位缺少票档：模板 " + seat.id()); }
            if (code != null) { result.put(seat.id(), code); }
        }
        return result;
    }

    private Session required(Session value) {
        if (value == null) { throw new BusinessException("NOT_FOUND", "场次不存在", HttpStatus.NOT_FOUND); }
        return value;
    }

    private BusinessException invalid(String message) {
        return new BusinessException("INVALID_SESSION", message, HttpStatus.BAD_REQUEST);
    }

    private BusinessException duplicateCode() {
        return new BusinessException("SESSION_CODE_EXISTS", "场次代码已存在，请按代码查询已有场次", HttpStatus.CONFLICT);
    }
}
