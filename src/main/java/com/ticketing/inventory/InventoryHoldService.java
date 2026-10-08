package com.ticketing.inventory;

import com.ticketing.common.persistence.GeneratedKey;
import com.ticketing.common.web.BusinessException;
import com.ticketing.inventory.InventoryHoldModels.Hold;
import com.ticketing.inventory.InventoryHoldModels.HoldStatus;
import com.ticketing.inventory.InventoryModels.SessionSeat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class InventoryHoldService {
    private final InventoryService inventory;
    private final InventoryHoldMapper mapper;
    private final SeatSelectionRules rules = new SeatSelectionRules();

    public InventoryHoldService(InventoryService inventory, InventoryHoldMapper mapper) {
        this.inventory = inventory;
        this.mapper = mapper;
    }

    /** Caller holds the session lock; full layout is freshly read in the caller's write transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<SessionSeat> validateSelection(long sessionId, List<Long> ids, Set<Long> enabledTierIds,
                                               boolean requireAdjacent, boolean forbidNewOrphan) {
        var layout = inventory.listSeats(sessionId);
        var byId = layout.stream().collect(Collectors.toMap(SessionSeat::id, seat -> seat));
        for (var id : ids) {
            var seat = byId.get(id);
            if (seat == null) { throw invalid("座位不存在或不属于本场次"); }
            if (seat.seatStatus() != InventoryModels.SeatStatus.AVAILABLE) {
                throw new BusinessException("SEAT_NOT_AVAILABLE", "所选座位已被占用或不可售", HttpStatus.CONFLICT);
            }
            if (!enabledTierIds.contains(seat.priceTierId())) {
                throw new BusinessException("PRICE_TIER_DISABLED", "所选座位票档未启用", HttpStatus.CONFLICT);
            }
        }
        var validation = rules.validate(layout, ids, requireAdjacent, forbidNewOrphan);
        if (!validation.valid()) {
            var status = Set.of("INVALID_SELECTION", "NOT_ADJACENT").contains(validation.code())
                    ? HttpStatus.BAD_REQUEST : HttpStatus.CONFLICT;
            throw new BusinessException(validation.code(), switch (validation.code()) {
                case "SEAT_NOT_AVAILABLE" -> "所选座位已被占用或不可售，请重新选座";
                case "NOT_ADJACENT" -> "推荐方式提交的组合必须连座且不跨过道";
                case "NEW_ORPHAN" -> "本次选座会制造新孤座，请重新选座";
                default -> "选座组合不合法";
            }, status);
        }
        return ids.stream().map(byId::get).sorted(Comparator.comparing(SessionSeat::id)).toList();
    }

    /** Every detail and every conditional seat update joins the same order transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Hold createHold(long orderId, long sessionId, List<SessionSeat> selected,
                           Instant at, Instant expiresAt) {
        if (selected.isEmpty() || selected.stream().anyMatch(seat -> seat.sessionId() != sessionId)) {
            throw new IllegalArgumentException("Hold seats must belong to one session");
        }
        var key = new GeneratedKey();
        mapper.insertHold(orderId, sessionId, expiresAt, at, key);
        for (var seat : selected.stream().sorted(Comparator.comparing(SessionSeat::id)).toList()) {
            mapper.insertItem(key.getId(), sessionId, seat.id(), at);
            if (mapper.holdSeat(sessionId, seat.id(), key.getId(), at) != 1) {
                throw new BusinessException("SEAT_NOT_AVAILABLE", "库存状态已变化，整组锁座失败", HttpStatus.CONFLICT);
            }
        }
        return new Hold(key.getId(), orderId, sessionId, HoldStatus.ACTIVE, expiresAt, at, at);
    }

    public Hold getByOrder(long orderId) { return mapper.byOrder(orderId); }

    private BusinessException invalid(String message) {
        return new BusinessException("INVALID_SELECTION", message, HttpStatus.BAD_REQUEST);
    }
}
