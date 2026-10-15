package com.ticketing.trade;

import com.ticketing.common.web.ApiResponse;
import com.ticketing.simulator.RefundSimulatorService;
import com.ticketing.simulator.RefundSimulatorModels.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static com.ticketing.trade.RefundModels.RefundRecord;

@RestController
@RequestMapping("/api/operations")
@SecurityRequirement(name="bearerAuth")
public class RefundOperationsController {
    private final RefundService refunds;
    private final RefundSimulatorService simulator;
    public RefundOperationsController(RefundService refunds,RefundSimulatorService simulator) { this.refunds=refunds; this.simulator=simulator; }
    @PostMapping("/refunds/{number}/succeed")
    @Operation(summary="演示渠道退款成功，仅提交渠道事实和原通知")
    public ApiResponse<Refund> succeed(@PathVariable String number) { return ApiResponse.success(simulator.succeed(number)); }
    @PostMapping("/refunds/{number}/fail")
    @Operation(summary="演示渠道退款明确失败，仅提交渠道事实和原通知")
    public ApiResponse<Refund> fail(@PathVariable String number) { return ApiResponse.success(simulator.fail(number)); }
    @GetMapping("/refunds/{number}/notifications")
    @Operation(summary="运营查看原退款持久通知")
    public ApiResponse<List<Notification>> notices(@PathVariable String number) { return ApiResponse.success(simulator.notifications(number)); }
    @PostMapping("/refund-notifications/{number}/deliver")
    @Operation(summary="投递原退款通知，本地提交后才确认送达")
    public ApiResponse<Void> deliver(@PathVariable String number) { refunds.deliver(number); return ApiResponse.success(null); }
    @PostMapping("/refunds/{refundId}/recover")
    @Operation(summary="运营恢复原退票单",description="可越过退避和人工标记，遵守活动租约；不能强行改成功或失败")
    public ApiResponse<RefundRecord> recover(@PathVariable @Positive long refundId) { return ApiResponse.success(refunds.recover(refundId)); }
    @GetMapping("/refunds/{refundId}/state")
    @Operation(summary="运营查看退款及恢复状态")
    public ApiResponse<RefundRecord> state(@PathVariable @Positive long refundId) { return ApiResponse.success(refunds.state(refundId)); }
}
