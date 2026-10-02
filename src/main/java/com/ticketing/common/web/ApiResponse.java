package com.ticketing.common.web;

import org.slf4j.MDC;

public record ApiResponse<T>(String code, String message, T data, String requestId) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("OK", "成功", data, MDC.get("requestId"));
    }

    public static <T> ApiResponse<T> failure(String code, String message, T data) {
        return new ApiResponse<>(code, message, data, MDC.get("requestId"));
    }
}
