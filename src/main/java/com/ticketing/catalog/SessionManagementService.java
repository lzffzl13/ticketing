package com.ticketing.catalog;

import com.ticketing.common.web.BusinessException;
import com.ticketing.inventory.InventoryService;
import com.ticketing.inventory.InventoryModels.SeatConfiguration;
import com.ticketing.inventory.InventoryModels.SeatStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;

import static com.ticketing.catalog.CatalogModels.*;
import static com.ticketing.catalog.CatalogRequests.*;

@Service
public class SessionManagementService {
    private final CatalogMapper mapper;
    private final InventoryService inventory;
    private final Clock clock;

    public SessionManagementService(CatalogMapper mapper, InventoryService inventory, Clock clock) {
        this.mapper = mapper;
        this.inventory = inventory;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Session configure(long sessionId, UpdateSessionConfiguration request) {
        // 所有管理写操作先锁同一场次行；等待开售提交后，必须按最新状态重新判断。
        var session = lock(sessionId);
        if (session.firstOpenedAt() != null) {
            throw conflict("SESSION_CONFIGURATION_FROZEN", "场次曾经开售，暂停也不能解除配置冻结");
        }
        validateTimes(request);
        var tiers = tierMap(sessionId);
        var editedIds = new HashSet<Long>();
        for (var edit : request.priceTiers()) {
            if (!editedIds.add(edit.id()) || !tiers.containsKey(edit.id())) {
                throw invalid("票档编辑重复或票档不属于本场次");
            }
            if (edit.price().signum() <= 0) { throw invalid("票价必须大于零"); }
        }
        for (var seat : request.seats()) {
            if (seat.priceTierId() != null && !tiers.containsKey(seat.priceTierId())) {
                throw invalid("座位票档不属于本场次");
            }
            if (seat.available() && seat.priceTierId() == null) { throw invalid("可售座位必须配置票档"); }
        }
        var at = clock.instant();
        mapper.updateConfiguration(sessionId, request, at);
        for (var edit : request.priceTiers()) {
            mapper.updateTier(sessionId, edit, at);
            inventory.refreshTierPrice(sessionId, edit.id(), edit.name(), edit.price(), at);
            var old = tiers.get(edit.id());
            tiers.put(edit.id(), new PriceTier(old.id(), sessionId, old.code(), edit.name(), edit.price(),
                    old.enabled(), old.createdAt(), at));
        }
        var seatChanges = new ArrayList<SeatConfiguration>();
        for (var seat : request.seats()) {
            var tier = tiers.get(seat.priceTierId());
            seatChanges.add(new SeatConfiguration(seat.id(), seat.priceTierId(), tier == null ? null : tier.name(),
                    tier == null ? null : tier.price(), seat.available()));
        }
        // 票档价格更新和单座重分配共用外层事务；任何后续异常回滚所有修改。
        inventory.configureSeats(sessionId, seatChanges, at);
        return mapper.sessionById(sessionId);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Session setSalesStatus(long sessionId, UpdateSalesStatus request) {
        var session = lock(sessionId);
        var target = request.salesStatus();
        if (target == SalesStatus.DRAFT || (session.salesStatus() == SalesStatus.DRAFT && target == SalesStatus.STOPPED)) {
            throw conflict("INVALID_SALES_TRANSITION", "场次只能首次开售、暂停或恢复，不能退回草稿");
        }
        // 设置当前状态是无副作用的成功；不会重写首次开售时间。
        if (target == session.salesStatus()) { return session; }
        if (target == SalesStatus.ON_SALE) { ensureSaleable(sessionId); }
        mapper.updateSalesStatus(sessionId, target, clock.instant());
        return mapper.sessionById(sessionId);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public PriceTier setTierEnabled(long sessionId, long tierId, SetTierEnabled request) {
        lock(sessionId);
        var tier = tierMap(sessionId).get(tierId);
        if (tier == null) { throw new BusinessException("NOT_FOUND", "本场次票档不存在", HttpStatus.NOT_FOUND); }
        if (tier.enabled() == request.enabled()) { return tier; }
        mapper.setTierEnabled(sessionId, tierId, request.enabled(), clock.instant());
        return tierMap(sessionId).get(tierId);
    }

    private void ensureSaleable(long sessionId) {
        var enabledIds = mapper.tiers(sessionId).stream().filter(PriceTier::enabled)
                .map(PriceTier::id).collect(Collectors.toSet());
        boolean saleable = inventory.listSeats(sessionId).stream()
                .anyMatch(seat -> seat.seatStatus() == SeatStatus.AVAILABLE && enabledIds.contains(seat.priceTierId()));
        if (!saleable) { throw conflict("SESSION_NOT_SALEABLE", "至少需要一个可售且票档启用的座位才能开售"); }
    }

    private Session lock(long sessionId) {
        var session = mapper.lockSession(sessionId);
        if (session == null) { throw new BusinessException("NOT_FOUND", "场次不存在", HttpStatus.NOT_FOUND); }
        return session;
    }

    private Map<Long, PriceTier> tierMap(long sessionId) {
        return mapper.tiers(sessionId).stream().collect(Collectors.toMap(PriceTier::id, tier -> tier));
    }

    private void validateTimes(UpdateSessionConfiguration request) {
        if (!request.admissionOpenAt().isBefore(request.admissionCloseAt())
                || request.admissionCloseAt().isAfter(request.startsAt())) {
            throw invalid("入场时间必须满足开放时间 < 截止时间 <= 开场时间");
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException("INVALID_SESSION_CONFIGURATION", message, HttpStatus.BAD_REQUEST);
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(code, message, HttpStatus.CONFLICT);
    }
}
