package com.ticketing.trade;

import com.ticketing.common.web.ApiResponse;
import com.ticketing.identity.IdentityModels.AuthenticatedAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static com.ticketing.trade.RefundModels.*;

@RestController
@RequestMapping("/api/orders/{orderId}")
@SecurityRequirement(name="bearerAuth")
public class RefundController {
    private final RefundService refunds;
    public RefundController(RefundService refunds) { this.refunds=refunds; }
    public record Request(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String requestKey) { }
    @PostMapping("/tickets/{ticketId}/refunds")
    @Operation(summary="幂等申请本人单张票全额退款",description="金额取原成交价，返回持久处理状态，不表示申请即退款成功")
    public ApiResponse<RefundRecord> start(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long orderId,@PathVariable @Positive long ticketId,@Valid @RequestBody Request request) {
        return ApiResponse.success(refunds.start(account.id(),orderId,ticketId,request.requestKey()));
    }
    @GetMapping("/refunds")
    @Operation(summary="本人订单退款历史及成功退款金额")
    public ApiResponse<RefundOrderState> order(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long orderId) { return ApiResponse.success(refunds.order(account.id(),orderId)); }
    @GetMapping("/refunds/{refundId}")
    @Operation(summary="查询本人原退票单")
    public ApiResponse<RefundRecord> get(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long orderId,@PathVariable @Positive long refundId) {
        return ApiResponse.success(refunds.owned(account.id(),orderId,refundId));
    }
}
