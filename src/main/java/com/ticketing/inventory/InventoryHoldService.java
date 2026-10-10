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
import java.util.HashSet;
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

    /** Caller already owns the session and order locks; the whole group joins its success transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Hold consume(long orderId, long sessionId, List<Long> expectedSeatIds, Instant at) {
        return finishGroup(orderId, sessionId, expectedSeatIds, HoldStatus.CONSUMED, at);
    }

    /** Caller has proved every payment cannot succeed, and keeps the session lock until commit. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Hold release(long orderId, long sessionId, List<Long> expectedSeatIds, boolean expired, Instant at) {
        return finishGroup(orderId, sessionId, expectedSeatIds,
                expired ? HoldStatus.EXPIRED : HoldStatus.RELEASED, at);
    }

    private Hold finishGroup(long orderId, long sessionId, List<Long> expectedSeatIds,
                             HoldStatus target, Instant at) {
        if (orderId <= 0 || sessionId <= 0 || at == null || expectedSeatIds == null || expectedSeatIds.isEmpty()
                || expectedSeatIds.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(expectedSeatIds).size() != expectedSeatIds.size()) {
            throw new IllegalArgumentException("A whole hold requires a nonempty distinct seat set and valid context");
        }
        var ids = expectedSeatIds.stream().sorted().toList();
        var hold = mapper.lockByOrder(orderId);
        if (hold == null || hold.sessionId() != sessionId || hold.status() != HoldStatus.ACTIVE) {
            throw ownershipConflict();
        }
        // Both history and current ownership must describe exactly the order's full seat set.
        if (!mapper.itemSeatIds(hold.id(), sessionId).equals(ids)
                || !mapper.ownedSeatIds(sessionId, hold.id()).equals(ids)) {
            throw ownershipConflict();
        }
        for (var id : ids) {
            var seat = mapper.lockSeat(sessionId, id);
            if (seat == null || seat.seatStatus() != InventoryModels.SeatStatus.HELD
                    || seat.currentHoldId() == null || !seat.currentHoldId().equals(hold.id())) {
                throw ownershipConflict();
            }
        }
        // Lock every seat in ascending order before any updates; SQL conditions remain the final guard.
        for (var id : ids) {
            int changed = target == HoldStatus.CONSUMED
                    ? mapper.consumeSeat(sessionId, id, hold.id(), at)
                    : mapper.releaseSeat(sessionId, id, hold.id(), at);
            if (changed != 1) { throw ownershipConflict(); }
        }
        if (mapper.finishHold(hold.id(), sessionId, target, at) != 1
                || !mapper.ownedSeatIds(sessionId, hold.id()).isEmpty()) {
            throw ownershipConflict();
        }
        return new Hold(hold.id(), hold.orderId(), hold.sessionId(), target,
                hold.expiresAt(), hold.createdAt(), at);
    }

    private BusinessException ownershipConflict() {
        return new BusinessException("HOLD_OWNERSHIP_CONFLICT", "订单锁组或座位归属已变化，整组处理已回滚",
                HttpStatus.CONFLICT);
    }

    private BusinessException invalid(String message) {
        return new BusinessException("INVALID_SELECTION", message, HttpStatus.BAD_REQUEST);
    }
}
