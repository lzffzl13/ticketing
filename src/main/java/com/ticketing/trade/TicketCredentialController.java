package com.ticketing.trade;

import com.ticketing.common.web.ApiResponse;
import com.ticketing.identity.IdentityModels.AuthenticatedAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Positive;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders/{orderId}/tickets/{ticketId}")
@SecurityRequirement(name = "bearerAuth")
public class TicketCredentialController {
    private final TicketCredentialService credentials;

    public TicketCredentialController(TicketCredentialService credentials) { this.credentials = credentials; }

    @GetMapping("/credential")
    @Operation(summary = "查询本人电子票入场凭证", description = "只读生成；真实入场仍须在线核对当前票状态、场次与时间并完成核销")
    public ResponseEntity<ApiResponse<TicketCredentialView>> credential(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long orderId, @PathVariable @Positive long ticketId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(credentials.credential(account.id(), orderId, ticketId)));
    }

    @GetMapping(value = "/qrcode", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "查询本人电子票二维码图片", description = "PNG 承载与凭证文本相同的票码规则；图片生成失败可重试，不重新出票")
    public ResponseEntity<byte[]> png(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long orderId, @PathVariable @Positive long ticketId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG)
                .body(credentials.png(account.id(), orderId, ticketId));
    }
}
