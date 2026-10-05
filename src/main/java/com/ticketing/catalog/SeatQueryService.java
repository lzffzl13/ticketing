package com.ticketing.catalog;

import com.ticketing.common.web.BusinessException;
import com.ticketing.inventory.InventoryModels.SessionSeat;
import com.ticketing.inventory.InventoryModels.SeatStatus;
import com.ticketing.inventory.InventoryService;
import com.ticketing.inventory.SeatRecommendationEngine;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;

import static com.ticketing.catalog.CatalogModels.*;
import static com.ticketing.catalog.SeatQueryModels.*;

@Service
public class SeatQueryService {
    private final SessionService sessions;
    private final InventoryService inventory;
    private final Clock clock;
    private final SeatRecommendationEngine engine = new SeatRecommendationEngine();

    public SeatQueryService(SessionService sessions, InventoryService inventory, Clock clock) {
        this.sessions = sessions;
        this.inventory = inventory;
        this.clock = clock;
    }

    // 普通一致性读，不锁资源；三个查询使用同一版本，返回后仍可能被其他事务改变。
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SeatMap seatMap(long sessionId) {
        var session = sessions.get(sessionId);
        var tiers = sessions.listTiers(sessionId).stream().collect(Collectors.toMap(PriceTier::id, tier -> tier));
        var layout = inventory.listSeats(sessionId).stream().sorted(layoutOrder()).toList();
        var groups = new LinkedHashMap<AreaKey, LinkedHashMap<RowKey, List<SeatMapSeat>>>();
        for (var seat : layout) {
            var tier = tiers.get(seat.priceTierId());
            boolean enabled = tier != null && tier.enabled();
            boolean selectable = session.salesStatus() == SalesStatus.ON_SALE
                    && seat.seatStatus() == SeatStatus.AVAILABLE && enabled;
            var view = new SeatMapSeat(seat.id(), seat.seatNumberSnapshot(), seat.coordinateXSnapshot(),
                    seat.coordinateYSnapshot(), seat.aisleSegmentSnapshot(), seat.priceTierId(),
                    seat.priceTierNameSnapshot(), seat.priceSnapshot(), seat.seatStatus(), enabled, selectable);
            var area = new AreaKey(seat.sourceAreaId(), seat.areaCodeSnapshot(), seat.areaNameSnapshot(),
                    seat.areaDisplayOrderSnapshot());
            var row = new RowKey(seat.rowLabelSnapshot(), seat.rowOrderSnapshot());
            groups.computeIfAbsent(area, key -> new LinkedHashMap<>())
                    .computeIfAbsent(row, key -> new ArrayList<>()).add(view);
        }
        var areas = new ArrayList<SeatMapArea>();
        for (var entry : groups.entrySet()) {
            var rows = entry.getValue().entrySet().stream()
                    .map(row -> new SeatMapRow(row.getKey().label(), row.getKey().order(), List.copyOf(row.getValue())))
                    .toList();
            areas.add(new SeatMapArea(entry.getKey().code(), entry.getKey().name(), entry.getKey().order(), rows));
        }
        return new SeatMap(sessionId, session.salesStatus(), session.forbidNewOrphan(), clock.instant(),
                false, List.copyOf(areas));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Recommendations recommend(long sessionId, int quantity, String areaCode, Long priceTierId, int limit) {
        if (quantity < 1 || limit < 1 || limit > 20) { throw invalid("人数必须为正整数，返回条数必须在 1–20 之间"); }
        var session = sessions.get(sessionId);
        if (session.salesStatus() != SalesStatus.ON_SALE) {
            throw new BusinessException("SESSION_NOT_ON_SALE", "场次尚未开售或已暂停销售", HttpStatus.CONFLICT);
        }
        var tiers = sessions.listTiers(sessionId);
        var layout = inventory.listSeats(sessionId);
        if (areaCode != null && layout.stream().noneMatch(seat -> seat.areaCodeSnapshot().equals(areaCode))) {
            throw invalid("区域不属于本场次");
        }
        if (priceTierId != null && tiers.stream().noneMatch(tier -> tier.id().equals(priceTierId))) {
            throw invalid("票档不属于本场次");
        }
        var enabledTierIds = tiers.stream().filter(PriceTier::enabled).map(PriceTier::id).collect(Collectors.toSet());
        var result = engine.recommend(layout, enabledTierIds, areaCode, priceTierId, quantity, limit,
                session.forbidNewOrphan());
        return new Recommendations(sessionId, quantity, clock.instant(), false, result.candidates(), result.diagnostics());
    }

    private Comparator<SessionSeat> layoutOrder() {
        return Comparator.comparingInt(SessionSeat::areaDisplayOrderSnapshot)
                .thenComparing(SessionSeat::areaCodeSnapshot).thenComparing(SessionSeat::sourceAreaId)
                .thenComparingInt(SessionSeat::rowOrderSnapshot).thenComparing(SessionSeat::rowLabelSnapshot)
                .thenComparingInt(SessionSeat::seatNumberSnapshot).thenComparing(SessionSeat::id);
    }

    private BusinessException invalid(String message) {
        return new BusinessException("INVALID_RECOMMENDATION", message, HttpStatus.BAD_REQUEST);
    }

    private record AreaKey(long id, String code, String name, int order) { }
    private record RowKey(String label, int order) { }
}
