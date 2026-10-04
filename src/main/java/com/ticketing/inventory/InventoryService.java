package com.ticketing.inventory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import com.ticketing.inventory.InventoryModels.SessionSeat;

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
}
