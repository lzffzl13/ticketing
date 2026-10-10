package com.ticketing.trade;

import com.ticketing.common.web.ApiResponse;
import com.ticketing.identity.IdentityModels.AuthenticatedAccount;
import com.ticketing.ticketing.TicketModels.Ticket;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static com.ticketing.trade.PaymentModels.*;

@RestController
@RequestMapping("/api/orders/{id}")
@SecurityRequirement(name = "bearerAuth")
public class PaymentController {
    private final PaymentService payments;
    public PaymentController(PaymentService payments) { this.payments = payments; }
    @PostMapping("/payments")
    @Operation(summary = "以原订单截止和后端金额幂等发起模拟支付", description = "返回本地持久处理状态；INITIATED 表示尚未确认渠道受理")
    public ApiResponse<PaymentRecord> start(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long id, @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.success(payments.start(account.id(), id, key));
    }
    @GetMapping("/payments")
    @Operation(summary = "查询本人订单的支付历史")
    public ApiResponse<List<PaymentRecord>> payments(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long id) { return ApiResponse.success(payments.payments(account.id(), id)); }
    @GetMapping("/tickets")
    @Operation(summary = "查询本人订单的电子票记录（本周票号不作为核销凭证）")
    public ApiResponse<List<Ticket>> tickets(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long id) { return ApiResponse.success(payments.tickets(account.id(), id)); }
    @PostMapping("/cancel")
    @Operation(summary = "请求安全取消", description = "未知支付返回待支付及取消意图；只有确定不能成功才整组释放")
    public ApiResponse<OrderPaymentState> cancel(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long id) { return ApiResponse.success(payments.cancel(account.id(), id)); }
}
