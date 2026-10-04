package com.ticketing.venue;

import com.ticketing.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

import static com.ticketing.venue.VenueModels.*;
import static com.ticketing.venue.VenueRequests.*;

@RestController
@RequestMapping("/api")
public class VenueController {
    private final VenueService service;

    public VenueController(VenueService service) { this.service = service; }

    @PostMapping("/venues")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Venue> createVenue(@Valid @RequestBody CreateVenue request) {
        return ApiResponse.success(service.createVenue(request));
    }

    @GetMapping("/venues/{id}")
    public ApiResponse<Venue> getVenue(@PathVariable @Positive long id) {
        return ApiResponse.success(service.getVenue(id));
    }

    @GetMapping("/venues/by-code/{code}")
    public ApiResponse<Venue> getVenueByCode(@PathVariable String code) {
        return ApiResponse.success(service.getVenueByCode(code));
    }

    @PutMapping("/venues/{id}")
    public ApiResponse<Venue> updateVenue(@PathVariable @Positive long id, @Valid @RequestBody UpdateVenue request) {
        return ApiResponse.success(service.updateVenue(id, request));
    }

    @PostMapping("/venues/{venueId}/areas")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Area> createArea(@PathVariable @Positive long venueId, @Valid @RequestBody CreateArea request) {
        return ApiResponse.success(service.createArea(venueId, request));
    }

    @GetMapping("/venues/{venueId}/areas")
    public ApiResponse<List<Area>> listAreas(@PathVariable @Positive long venueId) {
        return ApiResponse.success(service.listAreas(venueId));
    }

    @PutMapping("/areas/{id}")
    public ApiResponse<Area> updateArea(@PathVariable @Positive long id, @Valid @RequestBody UpdateArea request) {
        return ApiResponse.success(service.updateArea(id, request));
    }

    @PostMapping("/areas/{areaId}/seat-templates")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "原子批量添加座位模板", description = "任一座位非法或重复时整批回滚")
    public ApiResponse<List<SeatTemplate>> addSeats(@PathVariable @Positive long areaId,
            @RequestBody @NotEmpty List<@Valid SeatInput> request) {
        return ApiResponse.success(service.addSeats(areaId, request));
    }

    @GetMapping("/venues/{venueId}/seat-templates")
    public ApiResponse<List<LayoutSeat>> listSeats(@PathVariable @Positive long venueId) {
        return ApiResponse.success(service.listLayoutSeats(venueId));
    }

    @PutMapping("/seat-templates/{id}")
    public ApiResponse<SeatTemplate> updateSeat(@PathVariable @Positive long id, @Valid @RequestBody UpdateSeat request) {
        return ApiResponse.success(service.updateSeat(id, request));
    }

    @DeleteMapping("/seat-templates/{id}")
    public ApiResponse<Void> deleteSeat(@PathVariable @Positive long id) {
        service.deleteSeat(id);
        return ApiResponse.success(null);
    }
}
