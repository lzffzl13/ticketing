package com.ticketing.inventory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.HashSet;
import java.util.stream.Collectors;
import java.time.Instant;
import java.math.BigDecimal;
import com.ticketing.common.web.BusinessException;
import org.springframework.http.HttpStatus;
import com.ticketing.inventory.InventoryModels.SessionSeat;
import com.ticketing.inventory.InventoryModels.SeatConfiguration;
import com.ticketing.inventory.InventoryModels.SeatStatus;

@Service
public class InventoryService {
    private static final int INSERT_CHUNK_SIZE = 200;
    private final InventoryMapper mapper;

    public InventoryService(InventoryMapper mapper) { this.mapper = mapper; }

    /** Every chunk joins the caller's transaction; none commits independently. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createSnapshots(long sessionId, List<SessionSeat> seats) {
        if (seats.isEmpty() || seats.stream().anyMatch(seat -> !seat.sessionId().equals(sessionId))) {
            throw new IllegalArgumentException("Snapshots must all belong to the new session");
        }
        for (int start = 0; start < seats.size(); start += INSERT_CHUNK_SIZE) {
            mapper.insertSeats(seats.subList(start, Math.min(start + INSERT_CHUNK_SIZE, seats.size())));
        }
        if (mapper.countSeats(sessionId) != seats.size()) {
            throw new IllegalStateException("Snapshot count did not match the source layout");
        }
    }

    public List<SessionSeat> listSeats(long sessionId) {
        return mapper.seats(sessionId);
    }

    /** Caller holds the session row lock and has checked that it has never opened. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refreshTierPrice(long sessionId, long tierId, String name, BigDecimal price, Instant at) {
        mapper.refreshTierPrice(sessionId, tierId, name, price, at);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void configureSeats(long sessionId, List<SeatConfiguration> configurations, Instant at) {
        var seats = mapper.seats(sessionId).stream().collect(Collectors.toMap(SessionSeat::id, seat -> seat));
        var ids = new HashSet<Long>();
        for (var configuration : configurations) {
            if (!ids.add(configuration.id()) || !seats.containsKey(configuration.id())) {
                throw invalid("座位配置重复或座位不属于本场次");
            }
            var seat = seats.get(configuration.id());
            if (seat.seatStatus() != SeatStatus.AVAILABLE && seat.seatStatus() != SeatStatus.UNAVAILABLE) {
                throw new BusinessException("SEAT_NOT_CONFIGURABLE", "已锁定或已售座位不能修改配置", HttpStatus.CONFLICT);
            }
            boolean noPrice = configuration.priceTierId() == null && configuration.priceTierName() == null
                    && configuration.price() == null;
            boolean completePrice = configuration.priceTierId() != null && configuration.priceTierName() != null
                    && !configuration.priceTierName().isBlank() && configuration.price() != null
                    && configuration.price().signum() > 0;
            if ((!noPrice && !completePrice) || (configuration.available() && !completePrice)) {
                throw invalid("可售座位必须具有本场次票档和完整正数价格");
            }
        }
        for (var configuration : configurations) {
            var status = configuration.available() ? SeatStatus.AVAILABLE : SeatStatus.UNAVAILABLE;
            if (mapper.configureSeat(sessionId, configuration, status, at) != 1) {
                throw new BusinessException("SEAT_NOT_CONFIGURABLE", "座位状态已变化，请重新查询", HttpStatus.CONFLICT);
            }
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException("INVALID_SEAT_CONFIGURATION", message, HttpStatus.BAD_REQUEST);
    }
}
