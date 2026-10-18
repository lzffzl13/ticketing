package com.ticketing.consistency;

import com.ticketing.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;
import static com.ticketing.consistency.ConsistencyModels.SessionReport;

@RestController
@RequestMapping("/api/operations/sessions")
@SecurityRequirement(name = "bearerAuth")
public class ConsistencyController {
    private final ConsistencyService consistency;
    public ConsistencyController(ConsistencyService consistency) { this.consistency = consistency; }

    @GetMapping("/{id}/consistency")
    @Operation(summary = "运营查询单场次本地一致性快照", description = "只报告差异，不直接修复订单、票或库存；未决和人工恢复数量单列")
    public ApiResponse<SessionReport> inspect(@PathVariable @Positive long id) {
        return ApiResponse.success(consistency.inspect(id));
    }
}
