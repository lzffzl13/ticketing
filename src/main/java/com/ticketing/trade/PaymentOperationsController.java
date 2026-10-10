package com.ticketing.trade;

import com.ticketing.common.web.ApiResponse;
import com.ticketing.simulator.PaymentSimulatorModels.Notification;
import com.ticketing.simulator.PaymentSimulatorModels.Payment;
import com.ticketing.simulator.PaymentSimulatorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static com.ticketing.trade.PaymentModels.*;

@RestController
@RequestMapping("/api/operations")
@SecurityRequirement(name = "bearerAuth")
public class PaymentOperationsController {
    private final PaymentService payments;
    private final PaymentSimulatorService simulator;
    public PaymentOperationsController(PaymentService payments, PaymentSimulatorService simulator) {
        this.payments = payments; this.simulator = simulator;
    }
    @GetMapping("/orders/{id}/payment-state")
    @Operation(summary = "运营查看订单支付、取消与恢复状态")
    public ApiResponse<OrderPaymentState> state(@PathVariable @Positive long id) { return ApiResponse.success(payments.state(id)); }
    @PostMapping("/orders/{id}/recover")
    @Operation(summary = "运营主动恢复原支付", description = "允许越过退避，遵守活动领取；不能强制释放未知支付")
    public ApiResponse<OrderPaymentState> recover(@PathVariable @Positive long id) { return ApiResponse.success(payments.recover(id)); }
    @PostMapping("/payments/{number}/succeed")
    @Operation(summary = "演示渠道成功，只保存渠道事实与通知")
    public ApiResponse<Payment> succeed(@PathVariable String number) { return ApiResponse.success(simulator.succeed(number)); }
    @PostMapping("/payments/{number}/fail")
    @Operation(summary = "演示渠道失败，只保存渠道事实与通知")
    public ApiResponse<Payment> fail(@PathVariable String number) { return ApiResponse.success(simulator.fail(number)); }
    @GetMapping("/payments/{number}/notifications")
    @Operation(summary = "查看持久通知快照")
    public ApiResponse<List<Notification>> notifications(@PathVariable String number) { return ApiResponse.success(simulator.notifications(number)); }
    @PostMapping("/notifications/{number}/deliver")
    @Operation(summary = "投递或重投原通知，本地结果提交后才确认送达")
    public ApiResponse<Void> deliver(@PathVariable String number) { payments.deliver(number); return ApiResponse.success(null); }
}
