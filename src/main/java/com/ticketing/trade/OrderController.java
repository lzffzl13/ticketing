package com.ticketing.trade;

import com.ticketing.common.web.ApiResponse;
import com.ticketing.identity.IdentityModels.AuthenticatedAccount;
import com.ticketing.trade.TradeModels.OrderDetails;
import com.ticketing.trade.TradeRequests.CreateOrder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
@SecurityRequirement(name = "bearerAuth")
public class OrderController {
    private final OrderService orders;

    public OrderController(OrderService orders) { this.orders = orders; }

    @PostMapping
    @Operation(summary = "幂等下单并整组锁座", description = "消费者身份来自 Bearer 认证；同编号同内容返回原订单和原期限")
    public ResponseEntity<ApiResponse<OrderDetails>> create(@AuthenticationPrincipal AuthenticatedAccount account,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CreateOrder request) {
        var result = orders.create(account.id(), key, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.success(result.order()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询自己的订单及成交快照")
    public ApiResponse<OrderDetails> get(@AuthenticationPrincipal AuthenticatedAccount account,
                                         @PathVariable @Positive long id) {
        return ApiResponse.success(orders.get(account.id(), id));
    }
}
