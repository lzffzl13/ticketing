package com.ticketing.common.web;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemController {
    @GetMapping("/ping")
    @Operation(summary = "确认应用可响应请求")
    public ApiResponse<PingResult> ping() {
        return ApiResponse.success(new PingResult("UP"));
    }

    public record PingResult(String status) {
    }
}
