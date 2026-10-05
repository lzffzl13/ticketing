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
    private final SessionManagementService management;

    public CatalogController(EventService events, SessionService sessions, SessionManagementService management) {
        this.events = events;
        this.sessions = sessions;
        this.management = management;
    }

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

    @PutMapping("/sessions/{id}/configuration")
    @Operation(summary = "修改首次开售前的场次配置", description = "时间、票档与座位同一事务；曾开售后返回 409")
    public ApiResponse<Session> configureSession(@PathVariable @Positive long id,
                                                 @Valid @RequestBody UpdateSessionConfiguration request) {
        return ApiResponse.success(management.configure(id, request));
    }

    @PutMapping("/sessions/{id}/sales-status")
    @Operation(summary = "开售、暂停或恢复销售", description = "首次开售时间保留；重复设置当前状态无副作用")
    public ApiResponse<Session> setSalesStatus(@PathVariable @Positive long id,
                                              @Valid @RequestBody UpdateSalesStatus request) {
        return ApiResponse.success(management.setSalesStatus(id, request));
    }

    @PutMapping("/sessions/{id}/price-tiers/{tierId}/enabled")
    @Operation(summary = "设置票档销售开关", description = "不修改座位价格快照或库存状态")
    public ApiResponse<PriceTier> setTierEnabled(@PathVariable @Positive long id, @PathVariable @Positive long tierId,
                                                @Valid @RequestBody SetTierEnabled request) {
        return ApiResponse.success(management.setTierEnabled(id, tierId, request));
    }
}
