package com.ticketing.catalog;

import com.ticketing.common.web.ApiResponse;
import com.ticketing.inventory.InventoryModels.SessionSeat;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

import static com.ticketing.catalog.CatalogModels.*;
import static com.ticketing.catalog.CatalogRequests.*;

@RestController
@RequestMapping("/api")
public class CatalogController {
    private final EventService events;
    private final SessionService sessions;

    public CatalogController(EventService events, SessionService sessions) { this.events = events; this.sessions = sessions; }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Event> createEvent(@Valid @RequestBody CreateEvent request) {
        return ApiResponse.success(events.create(request));
    }

    @GetMapping("/events/{id}")
    public ApiResponse<Event> getEvent(@PathVariable @Positive long id) {
        return ApiResponse.success(events.get(id));
    }

    @GetMapping("/events/by-code/{code}")
    public ApiResponse<Event> eventByCode(@PathVariable String code) {
        return ApiResponse.success(events.getByCode(code));
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "创建 DRAFT 场次及独立座位快照", description = "场次、票档与全部座位同一事务；重复代码返回 409")
    public ApiResponse<CreatedSession> createSession(@Valid @RequestBody CreateSession request) {
        return ApiResponse.success(sessions.create(request));
    }

    @GetMapping("/sessions/{id}")
    public ApiResponse<Session> getSession(@PathVariable @Positive long id) {
        return ApiResponse.success(sessions.get(id));
    }

    @GetMapping("/sessions/by-code/{code}")
    public ApiResponse<Session> sessionByCode(@PathVariable String code) {
        return ApiResponse.success(sessions.getByCode(code));
    }

    @GetMapping("/sessions/{id}/price-tiers")
    public ApiResponse<List<PriceTier>> listTiers(@PathVariable @Positive long id) {
        return ApiResponse.success(sessions.listTiers(id));
    }

    @GetMapping("/sessions/{id}/seats")
    public ApiResponse<List<SessionSeat>> listSeats(@PathVariable @Positive long id) {
        return ApiResponse.success(sessions.listSeats(id));
    }
}
