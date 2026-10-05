package com.ticketing.catalog;

import com.ticketing.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;

import static com.ticketing.catalog.SeatQueryModels.*;

@RestController
@RequestMapping("/api/sessions/{id}")
public class SeatQueryController {
    private final SeatQueryService queries;

    public SeatQueryController(SeatQueryService queries) { this.queries = queries; }

    @GetMapping("/seat-map")
    @Operation(summary = "查询完整场次座位图", description = "库存状态与可选择资格分开；只读查询不占用座位")
    public ApiResponse<SeatMap> seatMap(@PathVariable @Positive long id) {
        return ApiResponse.success(queries.seatMap(id));
    }

    @GetMapping("/recommendations")
    @Operation(summary = "推荐确定性的连座候选", description = "同区同排同档连续且不跨过道；候选不等于锁座，下单需重新校验")
    public ApiResponse<Recommendations> recommend(@PathVariable @Positive long id,
                                                 @RequestParam @Positive int quantity,
                                                 @RequestParam(required = false) String areaCode,
                                                 @RequestParam(required = false) @Positive Long priceTierId,
                                                 @RequestParam(defaultValue = "5") @Min(1) @Max(20) int limit) {
        return ApiResponse.success(queries.recommend(id, quantity, areaCode, priceTierId, limit));
    }
}
