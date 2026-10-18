package com.ticketing.checkin;

import com.ticketing.common.web.ApiResponse;
import com.ticketing.identity.IdentityModels.AuthenticatedAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import static com.ticketing.checkin.CheckinModels.*;

@RestController
@RequestMapping("/api/checkins")
@SecurityRequirement(name = "bearerAuth")
public class CheckinController {
    private final CheckinService checkins;
    private final CheckinQueryService queries;
    public CheckinController(CheckinService checkins, CheckinQueryService queries) {
        this.checkins = checkins; this.queries = queries;
    }

    public record Request(@Positive long sessionId, @NotBlank @Size(max = 256) String credential) { }

    @PostMapping
    @Operation(summary = "工作人员在线核销并追加本次扫描审计", description = "每次受理扫描独立记录；首次SUCCESS，重复ALREADY_CHECKED_IN。业务拒绝仍返回结果；格式、鉴权错误不属于已受理扫描。")
    public ApiResponse<Attempt> scan(@AuthenticationPrincipal AuthenticatedAccount account,
                                     @Valid @RequestBody Request request) {
        return ApiResponse.success(checkins.scan(account.id(), request.sessionId(), request.credential(), MDC.get("requestId")));
    }

    @GetMapping
    @Operation(summary = "工作人员或运营查询核销审计", description = "按场次、票、[from,to)过滤；afterId游标升序，limit最大100。仅返回凭证SHA-256摘要。")
    public ApiResponse<Page> query(@RequestParam(required = false) Long sessionId,
                                   @RequestParam(required = false) Long ticketId,
                                   @RequestParam(required = false) Instant from,
                                   @RequestParam(required = false) Instant to,
                                   @RequestParam(defaultValue = "0") long afterId,
                                   @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(queries.query(sessionId, ticketId, from, to, afterId, limit));
    }
}
